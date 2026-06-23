package com.printkiosk.server.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@Slf4j
public class PageCountService {

    public int count(MultipartFile file, String mimeType) {
        try {
            return switch (mimeType) {
                case FileValidationService.MIME_PDF  -> countPdfPages(file);
                case FileValidationService.MIME_JPEG,
                     FileValidationService.MIME_PNG  -> 1;
                case FileValidationService.MIME_DOCX -> 1;  // TODO: точный подсчёт через POI
                default -> 1;
            };
        } catch (IOException e) {
            log.warn("Could not determine page count, defaulting to 1: {}", e.getMessage());
            return 1;
        }
    }

    private int countPdfPages(MultipartFile file) throws IOException {
        try (var in = file.getInputStream();
             PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            return doc.getNumberOfPages();
        }
    }
}
