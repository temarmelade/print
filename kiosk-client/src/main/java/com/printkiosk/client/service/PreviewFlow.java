package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.service.preview.PreviewService;
import com.printkiosk.client.service.preview.PreviewSession;
import com.printkiosk.shared.api.dto.VerifyResponse;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Скачивает файл с сервера, открывает через PreviewService и хранит
 * текущую сессию + индекс страницы. UI получает события через listener.
 * <p>
 * Жизненный цикл сессии:
 *  1. {@link #start(VerifyResponse)} — скачивание + открытие
 *  2. {@link #next()} / {@link #prev()} — листание страниц
 *  3. {@link #close()} — закрыть сессию, удалить временный файл
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewFlow {

    private final PreviewService previewService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Listener listener;
    private PreviewSession session;
    private Path           tempFile;
    private int            currentPage;
    private VerifyResponse currentFile;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Запустить превью для файла, найденного по PIN. Скачивает в фоне,
     * открывает PreviewSession, рендерит первую страницу.
     */
    public void start(VerifyResponse response) {
        close();
        this.currentFile = response;
        this.currentPage = 0;

        notifyLoading();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // 1. Скачать файл во временный путь
                Path downloaded = downloadFile(response.downloadUrl(),
                        response.originalFilename());
                // 2. Открыть PreviewSession
                PreviewSession opened = previewService.open(
                        downloaded, response.contentType());

                // Сохраняем в полях класса, а не локально:
                // используются на UI-потоке через listener-события.
                tempFile = downloaded;
                session  = opened;
                return null;
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            log.info("Preview ready: {} ({} pages)",
                    response.originalFilename(), session.getPageCount());
            renderCurrentPage();
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            Throwable cause = task.getException();
            log.error("Preview failed for {}", response.originalFilename(), cause);
            notifyError(humanizeError(cause));
        }));

        Thread t = new Thread(task, "preview-loader");
        t.setDaemon(true);
        t.start();
    }

    public void next() {
        if (session == null) return;
        if (currentPage + 1 >= session.getPageCount()) return;
        currentPage++;
        renderCurrentPage();
    }

    public void prev() {
        if (session == null) return;
        if (currentPage <= 0) return;
        currentPage--;
        renderCurrentPage();
    }

    /** Текущий локальный путь скачанного файла — нужен для печати. */
    public Path currentFilePath() {
        return tempFile;
    }

    public int currentPageIndex() {
        return currentPage;
    }

    public int totalPages() {
        return session != null ? session.getPageCount() : 0;
    }

    public void close() {
        if (session != null) {
            try { session.close(); }
            catch (Exception e) { log.warn("Failed to close preview session", e); }
            session = null;
        }
        if (tempFile != null) {
            try { Files.deleteIfExists(tempFile); }
            catch (IOException e) { log.warn("Failed to delete temp file {}", tempFile, e); }
            tempFile = null;
        }
        currentPage = 0;
        currentFile = null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════

    private Path downloadFile(String url, String originalName) throws IOException, InterruptedException {
        String ext = extensionFor(originalName);
        Path target = Files.createTempFile("kiosk-preview-", ext);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(req,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Server returned HTTP " + response.statusCode()
                    + " for " + url);
        }

        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Downloaded {} bytes to {}", Files.size(target), target);
        return target;
    }

    private void renderCurrentPage() {
        if (session == null) return;
        final int pageIndex = currentPage;

        Task<BufferedImage> task = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                return session.renderPage(pageIndex);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            BufferedImage awtImage = task.getValue();
            Image fxImage = SwingFXUtils.toFXImage(awtImage, null);
            notifyPageRendered(fxImage, pageIndex, session.getPageCount());
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            log.error("Render page {} failed", pageIndex, task.getException());
            notifyError("Не удалось отобразить страницу");
        }));

        Thread t = new Thread(task, "preview-render");
        t.setDaemon(true);
        t.start();
    }

    private static String extensionFor(String originalName) {
        if (originalName == null) return ".bin";
        int dot = originalName.lastIndexOf('.');
        return (dot < 0) ? ".bin" : originalName.substring(dot).toLowerCase();
    }

    private static String humanizeError(Throwable cause) {
        if (cause == null)                                          return "Неизвестная ошибка";
        if (cause instanceof IOException)                            return "Не удалось скачать файл";
        if (cause instanceof UnsupportedOperationException)          return "Формат файла не поддерживается";
        return "Ошибка загрузки документа";
    }

    private void notifyLoading()                                   { if (listener != null) listener.onLoading(); }
    private void notifyPageRendered(Image img, int idx, int total) { if (listener != null) listener.onPageRendered(img, idx, total); }
    private void notifyError(String msg)                           { if (listener != null) listener.onError(msg); }

    public interface Listener {
        void onLoading();
        void onPageRendered(Image image, int pageIndex, int totalPages);
        void onError(String message);
    }
}
