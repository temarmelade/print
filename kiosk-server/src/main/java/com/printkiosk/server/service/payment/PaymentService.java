package com.printkiosk.server.service.payment;

import com.printkiosk.server.domain.PrintJobEntity;
import com.printkiosk.server.domain.PrintJobRepository;
import com.printkiosk.server.exception.JobNotFoundException;
import com.printkiosk.server.exception.PaymentGatewayException;
import com.printkiosk.server.service.print.PrintJobService;
import com.printkiosk.shared.api.PrintJobStatus;
import com.printkiosk.shared.api.dto.PaymentSessionDto;
import com.printkiosk.shared.api.dto.PaymentStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Серверный сервис платежей.
 * <p>
 * Трекинг сессии идёт через PIN ({@code orderId = "PIN-" + pin}), потому что
 * Finik webhook возвращает только наш orderId, а не наш {@code paymentId}.
 * Это совпадает с архитектурой монолита и не требует двойной идентификации.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final String ORDER_ID_PREFIX = "PIN-";

    private final PrintJobRepository    jobs;
    private final PrintJobService       jobService;
    private final PaymentGateway        gateway;
    private final PaymentEventBus       eventBus;

    // ════════════════════════════════════════════════════════════════
    //  CREATE PAYMENT SESSION
    // ════════════════════════════════════════════════════════════════

    /**
     * Создаёт платёжную сессию для READY-job'а.
     * <p>
     * Идемпотентно: повторный вызов на job'е, у которого уже есть paymentId,
     * возвращает существующую сессию без обращения к Finik.
     */
    @Transactional
    public PaymentSessionDto createSession(UUID jobId) {
        PrintJobEntity job = jobs.findByIdWithFile(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        // Idempotency
        if (job.getPaymentId() != null) {
            log.info("Payment session already exists for job={}, returning existing", jobId);
            return new PaymentSessionDto(
                    job.getId(),
                    job.getPaymentId(),
                    job.getPaymentUrl(),
                    job.getPriceSom());
        }

        if (job.getStatus() != PrintJobStatus.READY) {
            throw new IllegalStateException(
                    "Cannot create payment for job in status " + job.getStatus());
        }

        // Берём PIN из снимка в задании: файл мог быть удалён по TTL,
        // job.getFile() уже может быть null (см. миграцию V8).
        String orderId = ORDER_ID_PREFIX + job.getPin();

        GatewayPaymentResult gwResult;
        try {
            gwResult = gateway.createPayment(orderId, job.getPriceSom());
        } catch (Exception e) {
            log.error("Payment gateway failed for job={}", jobId, e);
            throw new PaymentGatewayException(
                    "Платёжная система недоступна, попробуйте позже", e);
        }

        boolean attached = jobService.attachPayment(
                jobId, gwResult.paymentId(), gwResult.paymentUrl());

        if (!attached) {
            log.warn("Job {} changed status during payment creation", jobId);
            throw new IllegalStateException("Job status changed, please retry");
        }

        log.info("Payment session created: job={} paymentId={} priceSom={}",
                jobId, gwResult.paymentId(), job.getPriceSom());

        return new PaymentSessionDto(
                job.getId(),
                gwResult.paymentId(),
                gwResult.paymentUrl(),
                job.getPriceSom());
    }

    // ════════════════════════════════════════════════════════════════
    //  WEBHOOK FROM FINIK
    // ════════════════════════════════════════════════════════════════

    /**
     * Обрабатывает webhook от Finik. Подпись и timestamp уже проверены
     * в {@code FinikWebhookController}, сюда payload приходит доверенным.
     */
    @Transactional
    public void handleFinikWebhook(FinikWebhookPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Webhook payload is null");
        }

        String status = payload.status();
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Webhook status is empty");
        }

        String orderId = extractOrderId(payload);
        if (orderId == null || !orderId.startsWith(ORDER_ID_PREFIX)) {
            throw new IllegalArgumentException("Invalid orderId in webhook: " + orderId);
        }

        String pin = orderId.substring(ORDER_ID_PREFIX.length());
        if (pin.isBlank()) {
            throw new IllegalArgumentException("Empty PIN in orderId: " + orderId);
        }

        log.info("Processing Finik webhook: pin={}, status={}, transactionId={}",
                maskPin(pin), status, payload.transactionId());

        if ("SUCCEEDED".equalsIgnoreCase(status)) {
            boolean ok = jobService.applyPaidByPin(pin);
            if (ok) publishEventByPin(pin, PaymentEvent.Type.PAID);
            return;
        }

        if ("FAILED".equalsIgnoreCase(status)) {
            jobService.failByPin(pin);
            publishEventByPin(pin, PaymentEvent.Type.FAILED);
            return;
        }

        log.warn("Unknown Finik webhook status: {}", status);
    }

    /** Извлекаем orderId из {@code data.orderId} или {@code fields.orderId}. */
    private String extractOrderId(FinikWebhookPayload payload) {
        if (payload.data() != null && payload.data().get("orderId") != null) {
            return String.valueOf(payload.data().get("orderId"));
        }
        if (payload.fields() != null && payload.fields().get("orderId") != null) {
            return String.valueOf(payload.fields().get("orderId"));
        }
        return null;
    }

    private void publishEventByPin(String pin, PaymentEvent.Type type) {
        jobs.findLatestActiveByPin(pin, Instant.now()).ifPresent(job ->
                eventBus.publish(new PaymentEvent(pin, job.getId(), type, Instant.now())));
    }

    // ════════════════════════════════════════════════════════════════
    //  STATUS QUERY (polling fallback)
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PaymentStatusDto getStatusByPin(String pin) {
        return jobService.getPaymentStatusByPin(pin);
    }

    // ════════════════════════════════════════════════════════════════

    private static String maskPin(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }
}