package com.printkiosk.server.service.print;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.domain.FileRepository;
import com.printkiosk.server.domain.PrintJobEntity;
import com.printkiosk.server.domain.PrintJobRepository;
import com.printkiosk.server.exception.JobNotFoundException;
import com.printkiosk.server.exception.PinNotFoundException;
import com.printkiosk.server.service.PricingService;
import com.printkiosk.server.web.mapper.JobMapper;
import com.printkiosk.shared.api.OperationType;
import com.printkiosk.shared.api.PrintJobStatus;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Управление жизненным циклом задания на печать.
 * <p>
 * Все переходы между состояниями делегированы атомарным UPDATE'ам
 * репозитория с условием на текущий статус — это даёт идемпотентность
 * без оптимистической блокировки и закрывает гонки между webhook'ом
 * и действиями киоска.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrintJobService {

    private final PrintJobRepository jobs;
    private final FileRepository     files;
    private final PricingService     pricing;
    private final JobMapper          jobMapper;
    // ════════════════════════════════════════════════════════════════
    //  CREATE
    // ════════════════════════════════════════════════════════════════

    /**
     * Создаёт job под существующий активный файл. Цена считается на
     * сервере — клиенту не доверяем, иначе при обходе UI можно
     * напечатать за 0 сом.
     */
    @Transactional
    public JobResponse createJob(CreateJobRequest req, String kioskId) {
        FileEntity file = files.findActiveByCode(req.pin(), Instant.now())
                .orElseThrow(PinNotFoundException::new);

        PrintSettings settings = req.settings();
        int chargedPages = effectivePageCount(req.pages(), file.getPageCount());
        int priceSom = pricing.calculateTotal(chargedPages, settings, kioskId);   // ← с kioskId

        // Тип операции выводим из источника файла: ксерокопия и скан-на-печать
        // заливаются с source=COPY/SCAN, обычная печать — с WEBSITE/TELEGRAM.
        OperationType operationType = printOperationFor(file.getSource());

        PrintJobEntity job = PrintJobEntity.builder()
                .id(UuidCreator.getTimeOrderedEpoch())
                .file(file)
                // Снимок данных файла: файл удалится по TTL, транзакция останется.
                .pin(file.getCode())
                .fileName(file.getOriginalFilename())
                .pageCount(file.getPageCount())
                .printedPages(chargedPages)     // реально печатаемые страницы
                .copies(settings.copies())
                .colorMode(settings.colorMode())
                .doubleSided(settings.doubleSided())
                .orientation(settings.orientation())
                .paperSize(settings.paperSize())
                .priceSom(priceSom)
                .status(PrintJobStatus.READY)
                .operationType(operationType)
                .kioskId(kioskId)
                .createdAt(Instant.now())
                .build();

        jobs.save(job);

        log.info("Job created: id={} fileId={} op={} priceSom={} kiosk={}",
                job.getId(), file.getId(), operationType, priceSom, kioskId);

        return jobMapper.toResponse(job);
    }

    /**
     * Создаёт job для <b>цифровой доставки</b> отсканированного документа
     * (получение через сайт или Telegram). В отличие от печати, цена —
     * фиксированная плата за страницу ({@code pricePerPageSom}); тариф,
     * цвет и копии не участвуют. Печать сканов остаётся бесплатной и идёт
     * обычным трактом печати, поэтому здесь не обрабатывается.
     * <p>
     * Идемпотентно по PIN: повторный тап (пользователь переключился между
     * «веб» и «Telegram») переиспользует уже созданный активный job, а не
     * плодит дубли — иначе webhook по PIN пометил бы «последний» и
     * рассинхронизировал бы оплату.
     */
    @Transactional
    public JobResponse createScanDeliveryJob(String pin, int pricePerPageSom,
                                             OperationType operationType, String kioskId) {
        FileEntity file = files.findActiveByCode(pin, Instant.now())
                .orElseThrow(PinNotFoundException::new);

        // Идемпотентность: активный job на этот PIN уже есть → переиспользуем.
        var existing = jobs.findLatestActiveByPin(pin, Instant.now());
        if (existing.isPresent()) {
            log.info("Scan-delivery job already active for pin={}, reusing id={}",
                    maskPin(pin), existing.get().getId());
            return jobMapper.toResponse(existing.get());
        }

        int pages    = file.getPageCount();
        int priceSom = Math.max(0, pages) * Math.max(0, pricePerPageSom);

        PrintJobEntity job = PrintJobEntity.builder()
                .id(UuidCreator.getTimeOrderedEpoch())
                .file(file)
                .pin(file.getCode())
                .fileName(file.getOriginalFilename())
                .pageCount(pages)
                .printedPages(pages)     // не печатаем, но снимок страниц полезен для отчётности
                .copies(1)
                .colorMode("BW")
                .doubleSided(false)
                .orientation("PORTRAIT")
                .paperSize("A4")
                .priceSom(priceSom)
                .status(PrintJobStatus.READY)
                .operationType(operationType)
                .kioskId(kioskId)
                .createdAt(Instant.now())
                .build();

        jobs.save(job);

        log.info("Scan-delivery job created: id={} pin={} op={} pages={} priceSom={}",
                job.getId(), maskPin(pin), operationType, pages, priceSom);

        return jobMapper.toResponse(job);
    }

    // ════════════════════════════════════════════════════════════════
    //  PAYMENT TRANSITIONS
    // ════════════════════════════════════════════════════════════════

    /**
     * Привязать платёжный URL к job'у. Возвращает true, если job
     * действительно был в READY (защищает от повторного startPayment).
     */
    @Transactional
    public boolean attachPayment(UUID jobId, String paymentId, String paymentUrl) {
        int updated = jobs.markPaymentPending(jobId, paymentId, paymentUrl);
        if (updated == 0) {
            log.info("attachPayment ignored for job={}: wrong status or not found", jobId);
            return false;
        }
        log.info("Payment attached: job={} paymentId={}", jobId, paymentId);
        return true;
    }

    /**
     * Обработка успешного платежа из webhook Finik.
     * <p>
     * Идемпотентна: повторный webhook вернёт {@code false} без побочных
     * эффектов. Finik ретраит до 200 OK, поэтому повторы — норма.
     */
    @Transactional
    public boolean applyPaidWebhook(String paymentId) {
        int updated = jobs.markPaidByPaymentId(paymentId, Instant.now());
        if (updated == 0) {
            log.info("Webhook for paymentId={} ignored (already processed or wrong status)",
                    paymentId);
            return false;
        }
        log.info("Payment confirmed via webhook: paymentId={}", paymentId);
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  PRINTING TRANSITIONS (от киоска)
    // ════════════════════════════════════════════════════════════════

    /** Киоск начал физическую печать. Допустим только переход PAID → PRINTING. */
    @Transactional
    public boolean startPrinting(UUID jobId) {
        int updated = jobs.transition(jobId, PrintJobStatus.PAID, PrintJobStatus.PRINTING);
        if (updated > 0) log.info("Job {} → PRINTING", jobId);
        return updated > 0;
    }

    /** Печать завершена успешно. */
    @Transactional
    public boolean markCompleted(UUID jobId) {
        int updated = jobs.markCompleted(jobId, Instant.now());
        if (updated > 0) log.info("Job {} → COMPLETED", jobId);
        return updated > 0;
    }

    /** Аварийный переход в FAILED. Допустим из любого незавершённого состояния. */
    @Transactional
    public boolean markFailed(UUID jobId, String reason) {
        PrintJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        if (job.getStatus() == PrintJobStatus.COMPLETED
                || job.getStatus() == PrintJobStatus.FAILED) {
            return false;
        }
        job.setStatus(PrintJobStatus.FAILED);
        log.warn("Job {} → FAILED, reason: {}", jobId, reason);
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  READ
    // ════════════════════════════════════════════════════════════════

    /**
     * Статус оплаты по PIN. Используется клиентом для polling'а
     * экрана оплаты.
     */
    @Transactional(readOnly = true)
    public PaymentStatusDto getPaymentStatusByPin(String pin) {
        return jobs.findLatestActiveByPin(pin, Instant.now())
                .map(j -> new PaymentStatusDto(
                        j.getId(),
                        j.getStatus().name(),
                        j.getPaymentUrl()))
                .orElse(PaymentStatusDto.notFound());
    }

    /** Полное состояние job'а — для админки или диагностики. */
    @Transactional(readOnly = true)
    public JobResponse getJob(UUID jobId) {
        PrintJobEntity job = jobs.findByIdWithFile(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        return jobMapper.toResponse(job);
    }

    // ════════════════════════════════════════════════════════════════
    //  CLEANUP
    // ════════════════════════════════════════════════════════════════

    /**
     * Закрывает «зависшие» job'ы: READY/PAYMENT_PENDING старше 30 минут.
     * Работает независимо от cleanup-джоба файлов: даже если файл ещё
     * жив (например, через PIN зашли повторно), сам job всё равно
     * надо закрыть, иначе он будет вечно числиться активным.
     */
    @Transactional
    public int failStaleUnpaidJobs(Instant threshold) {
        int n = jobs.failStaleUnpaidJobs(threshold);
        if (n > 0) log.info("Failed {} stale unpaid jobs", n);
        return n;
    }

    @Scheduled(fixedRate = 300_000)   // каждые 5 минут
    @Transactional
    public void scheduledFailStaleJobs() {
        failStaleUnpaidJobs(Instant.now().minus(Duration.ofMinutes(30)));
    }

    @Transactional(readOnly = true)
    public JobPreviewResponse previewJob(JobPreviewRequest req, String kioskId) {
        FileEntity file = files.findActiveByCode(req.pin(), Instant.now())
                .orElseThrow(PinNotFoundException::new);

        PriceBreakdown breakdown = pricing.calculate(
                effectivePageCount(req.pages(), file.getPageCount()),
                req.settings(), kioskId);

        log.info("Job preview: fileId={} priceSom={} colorMode={} copies={}",
                file.getId(), breakdown.totalSom(),
                breakdown.colorMode(), breakdown.copies());

        return new JobPreviewResponse(
                file.getId(),
                file.getOriginalFilename(),
                breakdown);
    }

    /** Помечает активный (PAYMENT_PENDING) job для данного PIN как PAID. Идемпотентно. */
    @Transactional
    public boolean applyPaidByPin(String pin) {
        int updated = jobs.markPaidByPin(pin, Instant.now());
        if (updated > 0) {
            log.info("Payment confirmed via webhook: pin={}", maskPin(pin));
            return true;
        }
        log.info("Webhook for pin={} ignored (no active PAYMENT_PENDING job)", maskPin(pin));
        return false;
    }

    /** Помечает активный (PAYMENT_PENDING) job для PIN как FAILED. */
    @Transactional
    public boolean failByPin(String pin) {
        int updated = jobs.markFailedByPin(pin);
        if (updated > 0) {
            log.info("Payment FAILED via webhook: pin={}", maskPin(pin));
            return true;
        }
        return false;
    }

    private static String maskPin(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }

    /**
     * Тип операции печати по источнику файла: ксерокопия и печать скана
     * заливаются с source COPY/SCAN, всё остальное (сайт/Telegram-бот) —
     * обычная печать. Источник может быть {@code null} после удаления файла.
     */
    private static OperationType printOperationFor(UploadSource source) {
        if (source == null) return OperationType.PRINT;
        return switch (source) {
            case COPY -> OperationType.COPY;
            case SCAN -> OperationType.SCAN_PRINT;
            default   -> OperationType.PRINT;
        };
    }

    /**
     * Сколько страниц реально печатается/оплачивается. Валидирует выбранные
     * страницы (1..total, без дублей). Если список пуст/{@code null} или после
     * валидации не осталось валидных номеров — считаем все страницы файла.
     */
    private static int effectivePageCount(java.util.List<Integer> pages, int totalPages) {
        if (pages == null || pages.isEmpty()) return totalPages;
        long valid = pages.stream()
                .filter(p -> p != null && p >= 1 && p <= totalPages)
                .distinct()
                .count();
        return valid == 0 ? totalPages : (int) valid;
    }
}