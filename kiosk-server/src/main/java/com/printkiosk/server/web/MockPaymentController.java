package com.printkiosk.server.web;

import com.printkiosk.server.service.payment.FinikWebhookPayload;
import com.printkiosk.server.service.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Локальный имитатор оплаты Finik — только под профилем {@code local}.
 * <p>
 * Эндпоинт формирует payload в реальном формате Finik webhook
 * и сразу шлёт его в {@link PaymentService#handleFinikWebhook(FinikWebhookPayload)},
 * минуя проверку подписи. Это позволяет тестировать платёжный flow без боевого Finik.
 */
@Slf4j
@Profile("mock")
@RestController
@RequestMapping("/mock-payment")
@RequiredArgsConstructor
public class MockPaymentController {

    private final PaymentService paymentService;

    /** Симулирует SUCCEEDED-webhook. Принимает PIN, не paymentId — так удобнее тестировать. */
    @PostMapping("/{pin}/pay")
    public ResponseEntity<String> simulatePay(@PathVariable("pin") String pin) {
        FinikWebhookPayload payload = buildPayload(pin, "SUCCEEDED");
        paymentService.handleFinikWebhook(payload);
        log.info("MOCK: simulated SUCCEEDED webhook for PIN={}", pin);
        return ResponseEntity.ok("OK: PIN-" + pin + " marked as PAID");
    }

    /** Симулирует FAILED-webhook (для тестов error-flow). */
    @PostMapping("/{pin}/fail")
    public ResponseEntity<String> simulateFail(@PathVariable("pin") String pin) {
        FinikWebhookPayload payload = buildPayload(pin, "FAILED");
        paymentService.handleFinikWebhook(payload);
        log.info("MOCK: simulated FAILED webhook for PIN={}", pin);
        return ResponseEntity.ok("OK: PIN-" + pin + " marked as FAILED");
    }

    private FinikWebhookPayload buildPayload(String pin, String status) {
        long now = System.currentTimeMillis();
        return new FinikWebhookPayload(
                "mock-id-" + pin,
                "mock-tx-" + pin,
                status,
                100,                                    // amount
                100,                                    // net
                "mock-account-id",
                Map.of(),                               // fields
                Map.of("orderId", "PIN-" + pin),        // data — ключевое поле для PaymentService
                now,                                    // requestDate
                now,                                    // transactionDate
                "DEBIT",                                // transactionType
                "MOCK-RECEIPT-" + pin                   // receiptNumber
        );
    }
}