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

@Slf4j
@Service
public class ScanFlow {

    private final ScannerService scanner;

    private final List<Path> pages = new ArrayList<>();
    private int currentIndex = 0;
    private String fileName;
    private Path sessionDir;

    private Listener listener;

    public ScanFlow(ScannerService scanner) {
        this.scanner = scanner;
    }

    public void setListener(Listener listener) { this.listener = listener; }

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

    public void clear() {
        pages.clear();
        currentIndex = 0;
        fileName = null;
        deleteDirQuietly(sessionDir);
        sessionDir = null;
    }

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

    public void next()     { if (hasPages()) { currentIndex = (currentIndex + 1) % pages.size(); notifyPageChanged(); } }
    public void previous() { if (hasPages()) { currentIndex = (currentIndex - 1 + pages.size()) % pages.size(); notifyPageChanged(); } }

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

    public void rescanCurrent() {
        if (hasPages()) {
            deleteFileQuietly(pages.remove(currentIndex));
            if (currentIndex >= pages.size() && !pages.isEmpty()) {
                currentIndex = pages.size() - 1;
            }
        }
        scanNextPage();
    }

    public boolean hasPages()        { return !pages.isEmpty(); }
    public int pageCount()           { return pages.size(); }
    public int currentPageNumber()   { return pages.isEmpty() ? 0 : currentIndex + 1; }
    public String fileName()         { return fileName; }
    public List<Path> pages()        { return List.copyOf(pages); }

    public Image currentPreviewImage() {
        if (!hasPages()) return null;
        return new Image(pages.get(currentIndex).toUri().toString(),
                900, 0, true, true);
    }

    private static final SecureRandom RND = new SecureRandom();
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";

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

    private void notifyScanStarted()  { if (listener != null) listener.onScanStarted(); }
    private void notifyScanReady()    { if (listener != null) listener.onScanReady(); }
    private void notifyScanError(String m) { if (listener != null) listener.onScanError(m); }
    private void notifyPageChanged()  { if (listener != null) listener.onPageChanged(); }

    public interface Listener {
        void onScanStarted();
        void onScanReady();
        void onScanError(String message);
        void onPageChanged();
    }
}