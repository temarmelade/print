package com.printkiosk.client.service.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(name = "kiosk.scanner.mock", havingValue = "true", matchIfMissing = true)
public class MockScannerService implements ScannerService {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mock-scanner");
                t.setDaemon(true);
                return t;
            });

    private int pageCounter = 0;

    @Override
    public CompletableFuture<File> scanPage() {
        CompletableFuture<File> future = new CompletableFuture<>();
        scheduler.schedule(() -> {
            try {
                future.complete(generateTestPage(++pageCounter));
            } catch (Exception e) {
                future.completeExceptionally(new CompletionException(e));
            }
        }, 3, TimeUnit.SECONDS);
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isReady() {
        return CompletableFuture.completedFuture(true);
    }

    private File generateTestPage(int number) throws IOException {
        int w = 827, h = 1169;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.DARK_GRAY);
        g.drawRect(40, 40, w - 80, h - 80);
        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(64f));
        g.drawString("Скан — страница " + number, 120, h / 2);
        g.dispose();

        Path tmp = Files.createTempFile("scan_mock_", ".png");
        javax.imageio.ImageIO.write(img, "png", tmp.toFile());
        log.info("Mock scan produced page {} → {}", number, tmp);
        return tmp.toFile();
    }
}
