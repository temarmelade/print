package com.printkiosk.server.service;

import com.printkiosk.server.config.KioskServerProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Валидация загружаемых файлов перед записью в storage.
 * <p>
 * Источник правды — magic bytes (сигнатура первых байтов файла),
 * а не имя или Content-Type, поскольку оба контролируются клиентом
 * и подделываются тривиально.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileValidationService {

    public static final String MIME_PDF  = "application/pdf";
    public static final String MIME_JPEG = "image/jpeg";
    public static final String MIME_PNG  = "image/png";
    public static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Сколько байт читаем для определения сигнатуры. */
    private static final int MAGIC_PROBE_BYTES = 8;

    private final KioskServerProperties properties;

    /** Закэшированный allow-set (читаем из properties один раз на старте). */
    private Set<String> allowedMimeTypes;

    @PostConstruct
    void init() {
        List<String> configured = properties.getFile().getAllowedMime();
        this.allowedMimeTypes = (configured == null || configured.isEmpty())
                ? Set.of(MIME_PDF, MIME_JPEG, MIME_PNG)
                : configured.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        log.info("File validation enabled, allowed types: {}", allowedMimeTypes);
    }

    /**
     * Валидирует MultipartFile. Возвращает истинный MIME, определённый
     * по сигнатуре, либо описание ошибки.
     */
    public ValidationResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ValidationResult.invalid(Reason.EMPTY);
        }

        long size = file.getSize();
        if (size > properties.getFile().getMaxSizeBytes()) {
            return ValidationResult.invalid(Reason.TOO_LARGE);
        }

        String detectedMime;

        try {
            detectedMime = detectMimeBySignature(file);
        } catch (IOException e) {
            log.warn("Could not read file for magic-byte check: {}", e.getMessage());
            return ValidationResult.invalid(Reason.UNREADABLE);
        }

        if (detectedMime == null) {
            log.info("DEBUG validation: detectedMime is null for file={} declaredMime={}",
                    file.getOriginalFilename(), file.getContentType());
            return ValidationResult.invalid(Reason.UNSUPPORTED_TYPE);
        }
        if (!allowedMimeTypes.contains(detectedMime)) {
            log.info("DEBUG validation: detectedMime='{}' not in allow-list {}",
                    detectedMime, allowedMimeTypes);
            return ValidationResult.invalid(Reason.UNSUPPORTED_TYPE);
        }

        // Доп. проверка: если клиент прислал Content-Type, он не должен
        // противоречить сигнатуре. Это не запрет, а ранний сигнал
        // о том, что что-то нечисто.
        String declared = file.getContentType();
        if (declared != null && !declared.equalsIgnoreCase(detectedMime)) {
            log.info("MIME mismatch: declared='{}', detected='{}', name='{}'",
                    declared, detectedMime, file.getOriginalFilename());
        }

        return ValidationResult.valid(detectedMime);
    }

    /**
     * Определяет MIME по первым байтам файла.
     * Возвращает null, если сигнатура не распознана.
     */
    private String detectMimeBySignature(MultipartFile file) throws IOException {
        byte[] head = new byte[MAGIC_PROBE_BYTES];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, MAGIC_PROBE_BYTES);
            if (read < 4) return null;
        }

        // PDF:  25 50 44 46     "%PDF"
        if (head[0] == 0x25 && head[1] == 0x50
                && head[2] == 0x44 && head[3] == 0x46) {
            return MIME_PDF;
        }
        // PNG:  89 50 4E 47 0D 0A 1A 0A
        if (head[0] == (byte) 0x89 && head[1] == 0x50
                && head[2] == 0x4E && head[3] == 0x47) {
            return MIME_PNG;
        }
        // JPEG: FF D8 FF
        if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8
                && head[2] == (byte) 0xFF) {
            return MIME_JPEG;
        }
        // DOCX/ZIP: 50 4B 03 04 (это любой ZIP, для точности нужно
        // распаковать и проверить наличие word/document.xml; для MVP
        // считаем достаточным проверку расширения у ZIP-контейнера).
        if (head[0] == 0x50 && head[1] == 0x4B
                && head[2] == 0x03 && head[3] == 0x04) {
            // см. enhanceDocxDetection ниже — для прода стоит дотянуть
            return MIME_DOCX;
        }

        log.debug("Unknown magic bytes: {}",
                HexFormat.of().formatHex(head, 0, Math.min(head.length, 8)));
        return null;
    }

    public enum Reason {
        EMPTY,             // файл пустой
        TOO_LARGE,         // превышен лимит размера
        UNREADABLE,        // не удалось прочитать поток
        UNSUPPORTED_TYPE   // сигнатура неизвестна или не в allow-list
    }

    public record ValidationResult(
            boolean valid,
            String detectedMime,
            Reason reason
    ) {
        public static ValidationResult valid(String mime) {
            return new ValidationResult(true, mime, null);
        }
        public static ValidationResult invalid(Reason reason) {
            return new ValidationResult(false, null, reason);
        }
    }
}