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

    private void finalizeSuccess(UUID jobId, UUID fileId) {
        // Серверные действия — best-effort. Если упадёт, печать всё равно произошла.
        try {
            server.markCompleted(jobId);
            server.consumeFile(fileId);
            notifyCompleted();
        } catch (Exception e) {
            log.error("Print succeeded but server finalization failed", e);
            notifyFailed("Печать выполнена, но возникла проблема. Уведомите админа.");
        }
    }

    private void finalizeFailure(UUID jobId, String message) {
        try {
            server.markFailed(jobId);
            // ВАЖНО: файл НЕ consume'им. paymentStatus остаётся PAID.
            // Это создаёт запись "PAID-but-FAILED" для админского refund'а.
        } catch (Exception e) {
            log.warn("Failed to mark FAILED: {}", e.getMessage());
        }
        notifyFailed(message);
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