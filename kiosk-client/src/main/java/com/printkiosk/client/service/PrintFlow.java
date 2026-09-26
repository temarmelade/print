package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.printer.PrintExecutor;
import com.printkiosk.client.printer.PrintManager;
import com.printkiosk.client.printer.PrinterResult;
import com.printkiosk.shared.api.dto.PrintSettings;
import com.printkiosk.shared.api.dto.VerifyResponse;
import javafx.application.Platform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintFlow {

    private final KioskServerClient server;
    private final PrintManager      printManager;
    private final PrintExecutor     printExecutor;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Listener listener;
    private volatile boolean inProgress = false;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start(UUID jobId, VerifyResponse file, PrintSettings settings) {
        start(jobId, file, settings, null);
    }

    public void start(UUID jobId, VerifyResponse file, PrintSettings settings,
                      java.util.List<Integer> pages) {
        if (inProgress) {
            log.warn("Print already in progress");
            return;
        }
        if (file == null) {
            // Раньше это превращалось в NPE внутри конвейера и сырой текст
            // исключения на экране клиента.
            log.error("Print requested without a file, job={}", jobId);
            finalizeFailure(jobId, "Файл для печати не подготовлен");
            return;
        }
        inProgress = true;
        notifyStarted();

        // Один цельный pipeline: скачать → пометить PRINTING → печать → финал.
        CompletableFuture
                .supplyAsync(() -> downloadFile(file), printExecutor.executor())
                .thenCompose(tempFile -> {
                    server.startPrinting(jobId);
                    fxUpdateStatus("Отправляем на принтер...");
                    return printManager.printAsync(tempFile, file.contentType(), settings, pages)
                            .whenComplete((r, t) -> deleteQuietly(tempFile));
                })
                .whenComplete((result, throwable) -> Platform.runLater(() -> {
                    inProgress = false;
                    if (throwable != null) {
                        finalizeFailure(jobId, "Системная ошибка: " + throwable.getMessage());
                    } else if (result.success()) {
                        finalizeSuccess(jobId, file.id());
                    } else {
                        finalizeFailure(jobId, result.errorMessage());
                    }
                }));
    }

    // ── Pipeline stages ────────────────────────────────────────────

    private Path downloadFile(VerifyResponse file) {
        try {
            String ext = extensionFor(file.originalFilename());
            Path target = Files.createTempFile("kiosk-print-", ext);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(file.downloadUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(
                    req, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                throw new RuntimeException("Download HTTP " + resp.statusCode());
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Downloaded {} bytes", Files.size(target));
            return target;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Не удалось скачать файл: " + e.getMessage(), e);
        }
    }

    /**
     * Лист уже вышел — человеку сразу «Готово». Отчитаться серверу — фоном,
     * с повторами. Раньше сбой сети в этот момент показывал экран ошибки
     * («Печать выполнена, но возникла проблема»), хотя документ был напечатан,
     * а сетевые вызовы шли прямо в потоке интерфейса.
     */
    private void finalizeSuccess(UUID jobId, UUID fileId) {
        notifyCompleted();
        CompletableFuture.runAsync(() -> {
            if (!withRetries("markCompleted " + jobId, () -> server.markCompleted(jobId))) {
                log.error("Печать выполнена, но сервер не отметил задание {} выполненным", jobId);
            }
            try {
                server.consumeFile(fileId);
            } catch (Exception e) {
                log.warn("consumeFile {} не прошёл: {} — файл удалится по сроку", fileId, e.getMessage());
            }
        }, printExecutor.executor());
    }

    private void finalizeFailure(UUID jobId, String message) {
        log.error("Печать задания {} не удалась: {}", jobId, message);
        notifyFailed(message);
        // ВАЖНО: файл НЕ consume'им. paymentStatus остаётся PAID — это
        // запись «оплачено, но не напечатано» для возврата денег.
        // Причину отправляем серверу: по ней потом разбирают такие случаи.
        CompletableFuture.runAsync(() -> {
            if (!withRetries("markFailed " + jobId, () -> server.markFailed(jobId, message))) {
                log.error("Не удалось отметить задание {} ошибочным на сервере", jobId);
            }
        }, printExecutor.executor());
    }

    /** До трёх попыток с паузой 2 и 4 секунды: короткий сбой сети не должен терять статус. */
    private static boolean withRetries(String what, Runnable call) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                call.run();
                return true;
            } catch (Exception e) {
                log.warn("{}: попытка {} не удалась: {}", what, attempt, e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void deleteQuietly(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException e) { log.warn("Cleanup failed: {}", e.getMessage()); }
    }

    private void fxUpdateStatus(String message) {
        Platform.runLater(() -> {
            if (listener != null) listener.onStatus(message);
        });
    }

    private static String extensionFor(String name) {
        if (name == null) return ".bin";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".bin" : name.substring(dot).toLowerCase();
    }

    // ── Listener ───────────────────────────────────────────────────

    public interface Listener {
        void onStarted();
        void onStatus(String message);
        void onCompleted();
        void onFailed(String message);
    }

    private void notifyStarted()              { if (listener != null) listener.onStarted(); }
    private void notifyCompleted()            { if (listener != null) listener.onCompleted(); }
    private void notifyFailed(String message) { if (listener != null) listener.onFailed(message); }
}