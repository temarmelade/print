package com.printkiosk.client.service.scan;

import com.printkiosk.model.UploadSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ScanService {

    private static final String MIME_TYPE_PDF = "application/pdf";

    public ScanPage scanSinglePage(int pageNumber) throws Exception {
        Path scanDir = getScanTempDir();

        String fileName = "mock-scan-page-" + UUID.randomUUID() + ".png";
        Path imagePath = scanDir.resolve(fileName);

        BufferedImage image = createMockScannedPage(pageNumber);
        ImageIO.write(image, "png", imagePath.toFile());

        log.info("Mock scanned page created: {}", imagePath);

        return new ScanPage(
                imagePath.toAbsolutePath().toString(),
                pageNumber
        );
    }

    public ScanResult buildPdfFromPages(List<ScanPage> pages, UploadSource source) throws Exception {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("Нет отсканированных страниц");
        }

        Path scanDir = getScanTempDir();

        String fileName = switch (source) {
            case COPY -> "copy-job-" + UUID.randomUUID() + ".pdf";
            case SCAN -> "scan-job-" + UUID.randomUUID() + ".pdf";
            default -> "scan-result-" + UUID.randomUUID() + ".pdf";
        };

        Path pdfPath = scanDir.resolve(fileName);

        try (PDDocument document = new PDDocument()) {
            for (ScanPage page : pages) {
                PDPage pdfPage = new PDPage(PDRectangle.A4);
                document.addPage(pdfPage);

                PDImageXObject image = PDImageXObject.createFromFile(page.imagePath(), document);

                float pageWidth = PDRectangle.A4.getWidth();
                float pageHeight = PDRectangle.A4.getHeight();

                float margin = 36;
                float maxWidth = pageWidth - margin * 2;
                float maxHeight = pageHeight - margin * 2;

                float imageWidth = image.getWidth();
                float imageHeight = image.getHeight();

                float scale = Math.min(maxWidth / imageWidth, maxHeight / imageHeight);

                float drawWidth = imageWidth * scale;
                float drawHeight = imageHeight * scale;

                float x = (pageWidth - drawWidth) / 2;
                float y = (pageHeight - drawHeight) / 2;

                try (PDPageContentStream contentStream = new PDPageContentStream(document, pdfPage)) {
                    contentStream.drawImage(image, x, y, drawWidth, drawHeight);
                }
            }

            document.save(pdfPath.toFile());
        }

        long fileSize = Files.size(pdfPath);

        log.info("Mock scan PDF created: {}", pdfPath);

        return new ScanResult(
                pdfPath.toAbsolutePath().toString(),
                fileName,
                MIME_TYPE_PDF,
                fileSize,
                pages.size(),
                List.copyOf(pages)
        );
    }

    private Path getScanTempDir() throws Exception {
        Path tempDir = Paths.get(
                System.getProperty("user.home"),
                "Desktop",
                "KioskTempFiles",
                "scans"
        );

        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        return tempDir;
    }

    private BufferedImage createMockScannedPage(int pageNumber) {
        int width = 900;
        int height = 1250;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(230, 235, 245));
        g.fillRect(60, 60, width - 120, height - 120);

        g.setColor(Color.WHITE);
        g.fillRect(90, 90, width - 180, height - 180);

        g.setColor(new Color(20, 40, 75));
        g.setFont(new Font("Arial", Font.BOLD, 46));
        g.drawString("Mock scanned document", 150, 180);

        g.setFont(new Font("Arial", Font.BOLD, 34));
        g.drawString("Page " + pageNumber, 150, 240);

        g.setColor(new Color(90, 105, 130));
        g.setFont(new Font("Arial", Font.PLAIN, 28));

        int y = 330;
        for (int i = 1; i <= 14; i++) {
            g.drawString("This is a simulated scanned line of text number " + i + ".", 150, y);
            y += 55;
        }

        g.setColor(new Color(20, 120, 255));
        g.setStroke(new BasicStroke(6));
        g.drawRoundRect(145, 1000, 610, 110, 24, 24);

        g.setFont(new Font("Arial", Font.BOLD, 30));
        g.drawString("Preview for Print Kiosk scanning flow", 175, 1068);

        g.dispose();

        return image;
    }
}