package com.printkiosk.server.web;

import com.printkiosk.server.config.KioskServerProperties;
import com.printkiosk.server.service.payment.PaymentService;
import com.printkiosk.server.service.print.PrintJobService;
import com.printkiosk.shared.api.OperationType;
import com.printkiosk.shared.api.dto.JobResponse;
import com.printkiosk.shared.api.dto.PaymentSessionDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Оплата <b>цифровой доставки</b> отсканированного документа (получение
 * через сайт или Telegram). Один запрос делает всё, что нужно киоску:
 * создаёт job доставки под уже загруженный скан (цена — фиксированная
 * плата за страницу) и открывает под него платёжную сессию, возвращая
 * готовый платёжный QR-URL.
 * <p>
 * Дальше киоск подписывается на обычный SSE-поток оплаты по PIN
 * ({@code /api/payments/{pin}/stream}) и после webhook'а PAID меняет
 * платёжный QR на QR получения документа. Печать сканов сюда не
 * обращается — она бесплатна и идёт трактом печати.
 */
@RestController
@RequestMapping("/api/scan-delivery")
@RequiredArgsConstructor
public class ScanDeliveryController {

    private final PrintJobService       jobService;
    private final PaymentService        paymentService;
    private final KioskServerProperties properties;

    @PostMapping
    public ResponseEntity<PaymentSessionDto> create(
            @Valid @RequestBody CreateScanDeliveryRequest request,
            @RequestHeader(value = "X-Kiosk-Id", required = false) String kioskId) {

        int perPageSom = properties.getScanDelivery().getPricePerPageSom();
        OperationType operationType = request.channel().toOperationType();

        JobResponse job = jobService.createScanDeliveryJob(
                request.pin(), perPageSom, operationType, kioskId);
        PaymentSessionDto session = paymentService.createSession(job.id());

        return ResponseEntity.ok(session);
    }

    /**
     * Фиксирует фактический канал получения после подтверждения оплаты —
     * киоск вызывает при событии PAID. Если пользователь до оплаты
     * переключился между веб и Telegram, тип операции доставки
     * перезаписывается на реально выбранный канал.
     */
    @PostMapping("/finalize")
    public ResponseEntity<Void> finalizeChannel(@Valid @RequestBody FinalizeRequest request) {
        jobService.finalizeScanDeliveryChannel(
                request.pin(), request.channel().toOperationType());
        return ResponseEntity.noContent().build();
    }

    public record CreateScanDeliveryRequest(
            @NotNull @Pattern(regexp = "\\d{4}") String pin,
            @NotNull Channel channel) {}

    public record FinalizeRequest(
            @NotNull @Pattern(regexp = "\\d{4}") String pin,
            @NotNull Channel channel) {}

    /** Канал получения скана — определяет тип операции для аналитики. */
    public enum Channel {
        WEB      { @Override public OperationType toOperationType() { return OperationType.SCAN_DOWNLOAD_WEB;  } },
        TELEGRAM { @Override public OperationType toOperationType() { return OperationType.SCAN_SEND_TELEGRAM; } };

        public abstract OperationType toOperationType();
    }
}
