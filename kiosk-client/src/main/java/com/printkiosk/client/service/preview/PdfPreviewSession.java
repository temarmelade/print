package com.printkiosk.client.service.preview;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;


@Slf4j
class PdfPreviewSession implements PreviewSession {

    private static final float RENDER_DPI = 100f;

    private final Path file;
    private final boolean deleteOnClose;
    private final PDDocument document;
    private final PDFRenderer renderer;
    private boolean closed = false;

    PdfPreviewSession(Path file, boolean deleteOnClose) throws Exception {
        this.file = file;
        this.deleteOnClose = deleteOnClose;
        this.document = Loader.loadPDF(file.toFile());
        this.renderer = new PDFRenderer(document);
    }

    @Override
    public int getPageCount() {
        return document.getNumberOfPages();
    }

    @Override
    public BufferedImage renderPage(int pageIndex) throws Exception {
        if (closed) {
            throw new IllegalStateException("PreviewSession is already closed");
        }
        int total = document.getNumberOfPages();
        if (pageIndex < 0 || pageIndex >= total) {
            throw new IndexOutOfBoundsException(
                    "Page index " + pageIndex + " out of range [0, " + total + ")");
        }
        return renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            document.close();
        } catch (Exception e) {
            log.warn("Failed to close PDDocument for preview: {}", file, e);
        }

        if (deleteOnClose) {
            try {
                Files.deleteIfExists(file);
                log.debug("Temp preview PDF deleted: {}", file);
            } catch (Exception e) {
                log.warn("Failed to delete temp preview PDF: {}", file, e);
            }
        }
    }
}
