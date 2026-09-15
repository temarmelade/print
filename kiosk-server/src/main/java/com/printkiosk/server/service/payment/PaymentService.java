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
            // Картинку не храним в БД: при повторном запросе киоск
            // нарисует QR из ссылки. Это редкий путь — сессия уже создана
            // и человек, скорее всего, просто вернулся на экран.
            return new PaymentSessionDto(
                    job.getId(),
                    job.getPaymentId(),
                    job.getPaymentUrl(),
                    job.getPriceSom(),
                    null);
        }

        if (job.getStatus() != PrintJobStatus.READY) {
            throw new IllegalStateException(
                    "Cannot create payment for job in status " + job.getStatus());
        }

        // Берём PIN из снимка в задании: файл мог быть удалён по TTL,
        // job.getFile() уже может быть null (см. миграцию V8).
        // PIN переиспользуется: генератор берёт любой код, не занятый
        // СЕЙЧАС, поэтому вчерашний 1234 сегодня выдадут снова. Для Finik
        // это неважно — он присылает вебхук на конкретную операцию.
        // Bakai же спрашивают статус ПО ЭТОМУ ЖЕ идентификатору, и если
        // он повторится, банк вернёт статус старой оплаты — печать ушла бы
        // бесплатно. Поэтому добавляем короткий уникальный хвост.
        String orderId = ORDER_ID_PREFIX + job.getPin() + "-" + shortSuffix(job.getId());

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
                job.getPriceSom(),
                gwResult.qrImageBase64());
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

        String pin = extractPin(orderId);
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

    /**
     * Обрабатывает уведомление об оплате от Bakai.
     *
     * <p>У уведомления нет подписи, поэтому доверять одному факту запроса
     * нельзя: адрес колбэка может узнать посторонний. Защита строится на
     * том, что подделать нужно сразу три вещи — существующий operationID,
     * его статус и точную сумму задания.
     *
     * @param orderId       наш operationID вида {@code PIN-1234-a1b2c3d4}
     * @param success       банк сообщил об успешной оплате
     * @param amount        сумма из уведомления, сверяется со стоимостью
     * @param bankReference идентификатор операции в банке, для разбора
     */
    @Transactional
    public void handleBakaiCallback(String orderId, boolean success,
                                    java.math.BigDecimal amount, String bankReference) {
        String pin = extractPin(orderId);
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("Не удалось разобрать orderId: " + orderId);
        }

        var job = jobs.findByPaymentId(orderId).orElse(null);
        if (job == null) {
            // Чужой или устаревший идентификатор. Печатать по нему нечего.
            log.warn("Уведомление Bakai по неизвестному платежу {} — игнорируем", orderId);
            return;
        }

        log.info("Уведомление Bakai: pin={} успех={} сумма={} операция={}",
                maskPin(pin), success, amount, bankReference);

        if (!success) {
            jobService.failByPin(pin);
            publishEventByPin(pin, PaymentEvent.Type.FAILED);
            return;
        }

        // Сумма должна покрывать стоимость. Недоплата не должна открывать
        // печать: иначе оплата 1 сома вместо 50 даст тот же результат.
        if (amount == null || amount.intValue() < job.getPriceSom()) {
            log.error("Оплата не покрывает стоимость: пришло {}, нужно {} (pin={}). "
                    + "Печать НЕ разблокирована, требуется ручной разбор.",
                    amount, job.getPriceSom(), maskPin(pin));
            return;
        }

        // applyPaidByPin идемпотентен: повторное уведомление (банки ретраят,
        // если не получили ответ) не создаст вторую оплату.
        if (jobService.applyPaidByPin(pin)) {
            publishEventByPin(pin, PaymentEvent.Type.PAID);
        } else {
            log.info("Оплата по pin={} уже учтена — повторное уведомление", maskPin(pin));
        }
    }

    /**
     * Достаёт PIN из orderId вида {@code PIN-1234-a1b2c3d4}.
     * Хвост после второго дефиса игнорируется — он только для уникальности.
     */
    public static String extractPin(String orderId) {
        if (orderId == null || !orderId.startsWith(ORDER_ID_PREFIX)) return null;
        String rest = orderId.substring(ORDER_ID_PREFIX.length());
        int dash = rest.indexOf('-');
        return dash > 0 ? rest.substring(0, dash) : rest;
    }

    /**
     * Первые 8 символов id задания — достаточно, чтобы orderId не
     * повторился, и коротко для полей банка с ограничением длины.
     */
    private static String shortSuffix(java.util.UUID jobId) {
        return jobId.toString().substring(0, 8);
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