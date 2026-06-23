package com.printkiosk.client.service.preview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Открывает локально скачанный файл для превью.
 * <p>
 * На клиенте никаких DOCX/XLSX — сервер всё конвертирует до загрузки
 * в Docker volume. Поэтому здесь только три формата: PDF, JPG, PNG.
 */
@Slf4j
@Service
public class PreviewService {

    private static final String PDF_MIME  = "application/pdf";
    private static final String JPEG_MIME = "image/jpeg";
    private static final String PNG_MIME  = "image/png";

    public PreviewSession open(Path file, String mimeType) throws Exception {
        if (file == null) {
            throw new IllegalArgumentException("Preview file path is null");
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Preview file not found: " + file);
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("MIME type is required for preview");
        }

        String mime = mimeType.toLowerCase();
        return switch (mime) {
            case PDF_MIME             -> { log.debug("Opening PDF preview: {}", file);
                yield new PdfPreviewSession(file, false); }
            case JPEG_MIME, PNG_MIME  -> { log.debug("Opening image preview: {}", file);
                yield new ImagePreviewSession(file); }
            default -> throw new UnsupportedOperationException(
                    "Preview not supported for MIME type: " + mimeType);
        };
    }

    public boolean isPreviewSupported(String mimeType) {
        if (mimeType == null) return false;
        String mime = mimeType.toLowerCase();
        return PDF_MIME.equals(mime)
                || JPEG_MIME.equals(mime)
                || PNG_MIME.equals(mime);
    }
}
