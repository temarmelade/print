package com.printkiosk.server.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.config.KioskServerProperties;
import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.domain.FileRepository;
import com.printkiosk.server.exception.FileValidationException;
import com.printkiosk.server.exception.PinCollisionException;
import com.printkiosk.server.exception.PinLockedByOtherKioskException;
import com.printkiosk.server.exception.PinNotFoundException;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.UploadResponse;
import com.printkiosk.shared.api.dto.VerifyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Оркестратор работы с файлами на сервере.
 * <p>
 * Сценарий upload состоит из шагов с разной природой:
 * <ol>
 *   <li>Валидация (CPU, быстро).</li>
 *   <li>Запись в Docker volume (I/O, может быть медленно).</li>
 *   <li>Генерация PIN + INSERT в БД (транзакция, должна быть короткой).</li>
 * </ol>
 * Шаги 1–2 идут вне транзакции, шаг 3 — в коротком {@code @Transactional}-блоке.
 * Это намеренно: держать БД-соединение открытым на время записи 20 МБ
 * в volume — путь к выгоранию пула при пиковой нагрузке от бота.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    /** Сколько раз пытаемся пересгенерировать PIN при гонке UNIQUE-индекса. */
    private static final int MAX_PIN_RETRIES = 3;

    private final FileRepository           repository;
    private final FileValidationService    validator;
    private final FileStorageService       storage;
    private final PinGeneratorService      pinGenerator;
    private final KioskServerProperties    properties;
    private final PageCountService  pageCountService;
    private final DocumentConversionService converter;
    // ════════════════════════════════════════════════════════════════
    //  UPLOAD
    // ════════════════════════════════════════════════════════════════

    /**
     * Принимает файл, валидирует, сохраняет в volume, выдаёт PIN.
     * При гонке PIN-индекса делает до {@value #MAX_PIN_RETRIES} повторов.
     */
    public UploadResponse upload(MultipartFile file,
                                 UploadSource source,
                                 Long telegramUserId) throws IOException {

        // ── 1. Валидация по magic bytes ─────────────────────────────
        var validation = validator.validate(file);
        if (!validation.valid()) {
            throw new FileValidationException(validation.reason());
        }
        String trueMime = validation.detectedMime();
        log.info("DEBUG upload: detectedMime='{}', isDocx={}",
                trueMime,
                FileValidationService.MIME_DOCX.equals(trueMime));
        // ── 1.5. Если DOCX — конвертируем в PDF ─────────────────────
        if (FileValidationService.MIME_DOCX.equals(trueMime)) {
            MultipartFile converted = convertDocxToPdf(file);
            // Дальше работаем с конвертированным PDF —
            // PIN и pageCount будут считаться по нему.
            return uploadPdf(converted, source, telegramUserId);
        }

        // ── 1.6. Подсчёт страниц ────────────────────────────────────
        int pageCount = pageCountService.count(file, trueMime);

        // ── Остальное как было ──────────────────────────────────────
        UUID    id           = UuidCreator.getTimeOrderedEpoch();
        String  ext          = extensionFor(trueMime);
        String  storedName   = id + ext;
        String  originalName = sanitizeOriginalName(file.getOriginalFilename());

        storage.save(file, storedName);

        try {
            return persistWithRetry(id, storedName, originalName,
                    trueMime, file.getSize(), pageCount,
                    source, telegramUserId);
        } catch (Exception e) {
            storage.deleteQuietly(storedName);
            throw e;
        }
    }

    /**
     * Рекурсивный вызов upload(...) с уже сконвертированным PDF.
     * Валидация повторится и подтвердит, что это PDF — нормально,
     * это не "лишняя работа", а дешёвая страховка от подделки.
     */
    private UploadResponse uploadPdf(MultipartFile pdfFile,
                                     UploadSource source,
                                     Long telegramUserId) throws IOException {
        return upload(pdfFile, source, telegramUserId);
    }

    private MultipartFile convertDocxToPdf(MultipartFile docx) throws IOException {
        // 1. Временный файл для входного DOCX
        Path tempDocx = Files.createTempFile("kiosk-docx-", ".docx");
        Path tempPdf  = null;
        try {
            Files.copy(docx.getInputStream(), tempDocx, StandardCopyOption.REPLACE_EXISTING);

            // 2. Конвертация через LibreOffice
            DocumentConversionService.ConvertedDocument result;
            try {
                result = converter.convertToPdf(tempDocx.toString());
            } catch (Exception e) {
                log.error("DOCX → PDF conversion failed", e);
                throw new FileValidationException(
                        FileValidationService.Reason.UNSUPPORTED_TYPE);
            }
            tempPdf = Path.of(result.filePath());

            // 3. Читаем PDF-байты, оборачиваем в MultipartFile
            byte[] pdfBytes = Files.readAllBytes(tempPdf);
            String originalName = swapExtension(docx.getOriginalFilename(), ".pdf");

            return new ByteArrayMultipartFile(
                    pdfBytes,
                    originalName,
                    "application/pdf");
        } finally {
            // 4. Чистим временные файлы (исходник + результат конвертации)
            try { Files.deleteIfExists(tempDocx); } catch (IOException ignored) {}
            if (tempPdf != null) {
                try { Files.deleteIfExists(tempPdf); } catch (IOException ignored) {}
            }
        }
    }

    private static String swapExtension(String name, String newExt) {
        if (name == null || name.isBlank()) return "document" + newExt;
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + newExt;
    }

    // В FileService:
    public UploadResponse upload(InputStream content,
                                 long contentLength,
                                 String declaredMime,
                                 String originalFileName,
                                 UploadSource source,
                                 Long telegramUserId) throws IOException {

        // Адаптер InputStream → "файл-подобный" объект для валидатора.
        // Внутри валидация всё равно читает только первые байты для magic-check.
        byte[] bytes = content.readAllBytes();      // OK для 20 МБ лимита

        if (bytes.length != contentLength && contentLength > 0) {
            log.warn("Content-Length mismatch: declared={}, actual={}", contentLength, bytes.length);
        }

        MultipartFile fakeUpload = new ByteArrayMultipartFile(
                bytes, originalFileName, declaredMime);

        return upload(fakeUpload, source, telegramUserId);
    }

    private UploadResponse persistWithRetry(UUID id, String storedName,
                                            String originalName, String mime,
                                            long size, int pageCount,
                                            UploadSource source, Long tgUserId) {
        Duration ttl = properties.getPin().getTtl();

        for (int attempt = 1; attempt <= MAX_PIN_RETRIES; attempt++) {
            try {
                return persist(id, storedName, originalName, mime, size, pageCount,
                        source, tgUserId, ttl);
            } catch (PinCollisionException collision) {
                if (attempt == MAX_PIN_RETRIES) throw collision;
                log.warn("PIN collision (attempt {}/{}), retrying", attempt, MAX_PIN_RETRIES);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Transactional
    protected UploadResponse persist(UUID id, String storedName, String originalName,
                                     String mime, long size, int pageCount,
                                     UploadSource source, Long tgUserId,
                                     Duration ttl) {
        Instant now = Instant.now();
        String  pin = pinGenerator.pickUnusedPin();

        FileEntity entity = FileEntity.builder()
                .id(id)
                .code(pin)
                .storedFilename(storedName)
                .originalFilename(originalName)
                .contentType(mime)
                .fileSize(size)
                .pageCount(pageCount)        // ← вот сюда
                .source(source)
                .telegramUserId(tgUserId)
                .createdAt(now)
                .expiresAt(now.plus(ttl))
                .build();

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException dup) {
            throw new PinCollisionException(pin, dup);
        }

        log.info("Uploaded file id={} pin={} source={} size={}B",
                id, pin, source, size);

        return new UploadResponse(pin, entity.getExpiresAt(), ttl.getSeconds());
    }

    // ════════════════════════════════════════════════════════════════
    //  VERIFY (киоск)
    // ════════════════════════════════════════════════════════════════

    @Transactional
    public VerifyResponse verify(String pin, String kioskId) {
        Instant now = Instant.now();

        // 1. Файл должен существовать, быть активным и не consumed.
        FileEntity file = repository.findActiveByCode(pin, now)
                .orElseThrow(PinNotFoundException::new);

        // 2. Пытаемся закрепить PIN за этим киоском (свободен / свой / протух hold).
        Duration holdTtl = properties.getPin().getTtl();
        int acquired = repository.acquireHold(pin, kioskId, now, now.plus(holdTtl));

        if (acquired == 0) {
            // UPDATE не прошёл. Между findActiveByCode и acquireHold возможна гонка
            // (cleanup/consume), поэтому перечитываем и различаем причину.
            FileEntity recheck = repository.findActiveByCode(pin, now)
                    .orElseThrow(PinNotFoundException::new);

            // PIN уже закреплён за каким-то киоском и hold ещё жив — отказ.
            // Намеренно не делаем исключения для «своего» kioskId: однажды
            // введённый код заблокирован до конца TTL для всех, включая
            // терминал, который его ввёл.
            boolean held =
                    recheck.getHolderKioskId() != null
                            && recheck.getHolderExpiresAt() != null
                            && recheck.getHolderExpiresAt().isAfter(now);

            if (held) {
                log.info("PIN {} verify rejected: held by kiosk={}, requested by={}",
                        pin, recheck.getHolderKioskId(), kioskId);
                throw new PinLockedByOtherKioskException();
            }
            // Иначе — состояние изменилось под нами; трактуем как «не найден».
            throw new PinNotFoundException();
        }

        log.info("PIN {} held by kiosk={} until {}", pin, kioskId, now.plus(holdTtl));

        String url = buildPublicUrl(file.getStoredFilename());
        return new VerifyResponse(
                file.getId(),
                url,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getFileSize(),
                file.getExpiresAt()
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  CONSUME (после успешной печати)
    // ════════════════════════════════════════════════════════════════

    /**
     * Атомарно помечает файл использованным. Возвращает true только
     * если этот вызов реально был первым. Cleanup-джоб удалит файл
     * физически по обычному expires_at-расписанию.
     */
    @Transactional
    public boolean markConsumed(UUID id) {
        int updated = repository.markConsumed(id, Instant.now());
        if (updated > 0) {
            log.info("File id={} marked as consumed", id);
            return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутренние утилиты
    // ════════════════════════════════════════════════════════════════

    private String buildPublicUrl(String storedFilename) {
        String base = properties.getStorage().getPublicBaseUrl();
        // отрезаем хвостовой / если случайно прописан в yml
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/files/" + storedFilename;
    }

    /**
     * Расширение по доверенному MIME (от валидатора), не по имени от клиента.
     * Это страхует от {@code evil.exe → renamed.pdf}: на диск файл
     * ляжет как {@code <uuid>.pdf}, что согласуется с реальной сигнатурой.
     */
    private String extensionFor(String trueMime) {
        return switch (trueMime) {
            case FileValidationService.MIME_PDF  -> ".pdf";
            case FileValidationService.MIME_JPEG -> ".jpg";
            case FileValidationService.MIME_PNG  -> ".png";
            case FileValidationService.MIME_DOCX -> ".docx";
            default -> "";
        };
    }

    /**
     * Имя файла от клиента храним только для UX («Распечатать Курсовая.pdf»).
     * Чистим control-символы и обрезаем длину — это поле уходит на киоск
     * и в логи, его лучше не оставлять «как пришло».
     */
    private String sanitizeOriginalName(String raw) {
        if (raw == null || raw.isBlank()) return "document";
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\\\/]", "_").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned.isBlank() ? "document" : cleaned;
    }
}