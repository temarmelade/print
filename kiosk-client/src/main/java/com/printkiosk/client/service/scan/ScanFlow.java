package com.printkiosk.client.service.scan;

import javafx.application.Platform;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Состояние и логика сессии сканирования. По образцу PinEntryFlow/
 * PrintSettingsFlow: держит стейт, оркеструет ScannerService, уведомляет UI
 * через Listener; сам JavaFX-узлы не трогает.
 * <p>
 * Страницы хранятся как пути к временным файлам (не Image в памяти): скан A4
 * в полном разрешении — десятки МБ, держать их пачкой в куче нельзя. Для
 * предпросмотра Image грузится лениво по индексу.
 */
@Slf4j
@Service
public class ScanFlow {

    private final ScannerService scanner;

    /** Пути к отсканированным страницам по порядку. */
    private final List<Path> pages = new ArrayList<>();
    private int currentIndex = 0;
    private String fileName;
    private Path sessionDir;

    private Listener listener;

    public ScanFlow(ScannerService scanner) {
        this.scanner = scanner;
    }

    // ════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════

    public void setListener(Listener listener) { this.listener = listener; }

    /**
     * Старт новой сессии: задаёт имя файла (пустое → случайное) и создаёт
     * временную папку. Старую сессию перед этим вычищает.
     */
    public void startSession(String rawName) {
        clear();
        this.fileName = (rawName == null || rawName.isBlank())
                ? randomName()
                : rawName.trim();
        try {
            sessionDir = Files.createTempDirectory("scan-session-");
        } catch (IOException e) {
            log.error("Cannot create scan session dir", e);
        }
        log.info("Scan session started: name='{}', dir={}", fileName, sessionDir);
    }

    /** Полная очистка сессии и удаление временных файлов. */
    public void clear() {
        pages.clear();
        currentIndex = 0;
        fileName = null;
        deleteDirQuietly(sessionDir);
        sessionDir = null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Сканирование
    // ════════════════════════════════════════════════════════════════

    /**
     * Сканировать страницу. Результат добавляется в конец, индекс встаёт на
     * новую страницу. UI уведомляется через listener (на FX-потоке).
     */
    public void scanNextPage() {
        notifyScanStarted();
        scanner.scanPage().whenComplete((file, err) -> Platform.runLater(() -> {
            if (err != null) {
                log.warn("Scan failed", err);
                notifyScanError(err.getMessage());
                return;
            }
            Path stored = moveIntoSession(file.toPath());
            pages.add(stored);
            currentIndex = pages.size() - 1;
            notifyScanReady();
        }));
    }

    // ════════════════════════════════════════════════════════════════
    //  Навигация по страницам (предпросмотр)
    // ════════════════════════════════════════════════════════════════

    public void next()     { if (hasPages()) { currentIndex = (currentIndex + 1) % pages.size(); notifyPageChanged(); } }
    public void previous() { if (hasPages()) { currentIndex = (currentIndex - 1 + pages.size()) % pages.size(); notifyPageChanged(); } }

    /**
     * «Удалить»: убирает текущую страницу. Если была последняя — сообщает UI
     * вернуться на экран прогресса; иначе показывает соседнюю.
     *
     * @return true, если страниц не осталось (UI → SCAN_PROGRESS)
     */
    public boolean deleteCurrent() {
        if (!hasPages()) return true;
        Path removed = pages.remove(currentIndex);
        deleteFileQuietly(removed);
        if (pages.isEmpty()) {
            currentIndex = 0;
            return true;
        }
        if (currentIndex >= pages.size()) currentIndex = pages.size() - 1;
        notifyPageChanged();
        return false;
    }

    /** «Пересканировать»: удаляет текущую и уводит на новый скан. */
    public void rescanCurrent() {
        if (hasPages()) {
            deleteFileQuietly(pages.remove(currentIndex));
            if (currentIndex >= pages.size() && !pages.isEmpty()) {
                currentIndex = pages.size() - 1;
            }
        }
        scanNextPage();
    }

    // ════════════════════════════════════════════════════════════════
    //  Доступ к данным (для печати/доставки)
    // ════════════════════════════════════════════════════════════════

    public boolean hasPages()        { return !pages.isEmpty(); }
    public int pageCount()           { return pages.size(); }
    public int currentPageNumber()   { return pages.isEmpty() ? 0 : currentIndex + 1; }
    public String fileName()         { return fileName; }
    public List<Path> pages()        { return List.copyOf(pages); }

    /** Лениво грузит Image текущей страницы для предпросмотра. */
    public Image currentPreviewImage() {
        if (!hasPages()) return null;
        return new Image(pages.get(currentIndex).toUri().toString(),
                900, 0, true, true);   // ограничиваем ширину, фоновая загрузка
    }

    /**
     * Собирает все отсканированные страницы в один PDF (для ксерокопии:
     * PDF заливается на сервер как обычный файл печати). Каждая страница —
     * изображение, вписанное в лист A4. Возвращает путь к PDF во временной
     * папке сессии.
     */
    public java.io.File buildPdf() throws IOException {
        org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        try {
            for (Path page : pages) {
                var pdPage = new org.apache.pdfbox.pdmodel.PDPage(
                        org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
                doc.addPage(pdPage);
                var img = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                        .createFromFileByExtension(page.toFile(), doc);

                float pw = pdPage.getMediaBox().getWidth();
                float ph = pdPage.getMediaBox().getHeight();
                float scale = Math.min(pw / img.getWidth(), ph / img.getHeight());
                float w = img.getWidth() * scale;
                float h = img.getHeight() * scale;
                float x = (pw - w) / 2;
                float y = (ph - h) / 2;

                try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(img, x, y, w, h);
                }
            }
            Path out = (sessionDir != null ? sessionDir : Files.createTempDirectory("scan-pdf-"))
                    .resolve((fileName != null ? fileName : "scan") + ".pdf");
            doc.save(out.toFile());
            log.info("Scan PDF built: {} page(s) → {}", pages.size(), out);
            return out.toFile();
        } finally {
            doc.close();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    private static final SecureRandom RND = new SecureRandom();
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** Случайное имя файла из букв и цифр (для пустого поля). */
    private static String randomName() {
        StringBuilder sb = new StringBuilder("scan_");
        for (int i = 0; i < 8; i++) sb.append(ALPHANUM.charAt(RND.nextInt(ALPHANUM.length())));
        return sb.toString();
    }

    private Path moveIntoSession(Path tmp) {
        if (sessionDir == null) return tmp;
        try {
            Path target = sessionDir.resolve("page_" + (pages.size() + 1) + ".png");
            Files.move(tmp, target);
            return target;
        } catch (IOException e) {
            log.warn("Cannot move scan into session dir, keeping temp file", e);
            return tmp;
        }
    }

    private void deleteFileQuietly(Path p) {
        try { if (p != null) Files.deleteIfExists(p); }
        catch (IOException e) { log.warn("Cannot delete scan file {}", p, e); }
    }

    private void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteFileQuietly);
        } catch (IOException e) {
            log.warn("Cannot clean scan session dir {}", dir, e);
        }
    }

    // ── listener bridge ────────────────────────────────────────────
    private void notifyScanStarted()  { if (listener != null) listener.onScanStarted(); }
    private void notifyScanReady()    { if (listener != null) listener.onScanReady(); }
    private void notifyScanError(String m) { if (listener != null) listener.onScanError(m); }
    private void notifyPageChanged()  { if (listener != null) listener.onPageChanged(); }

    /** UI-события сессии сканирования. */
    public interface Listener {
        /** Скан запущен → показать экран прогресса. */
        void onScanStarted();
        /** Скан получен → показать предпросмотр текущей страницы. */
        void onScanReady();
        /** Ошибка сканера. */
        void onScanError(String message);
        /** Сменилась текущая страница (перелистывание/удаление). */
        void onPageChanged();
    }
}