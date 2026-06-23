package com.printkiosk.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printkiosk.server.service.payment.FinikWebhookPayload;
import com.printkiosk.server.service.payment.FinikWebhookVerifier;
import com.printkiosk.server.service.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class FinikWebhookController {

    private final FinikWebhookVerifier verifier;
    private final PaymentService       paymentService;
    private final ObjectMapper         objectMapper;

    @PostMapping("/finik")
    public ResponseEntity<Void> handle(@RequestBody String rawBody,
                                       HttpServletRequest request) {

        if (!verifier.verify(request, rawBody)) {
            return ResponseEntity.status(401).build();
        }

        try {
            FinikWebhookPayload payload = objectMapper.readValue(rawBody, FinikWebhookPayload.class);
            paymentService.handleFinikWebhook(payload);
        } catch (Exception e) {
            log.error("Webhook processing failed", e);
            // 200 OK даже при логических ошибках — иначе Finik будет ретраить навсегда.
            // Сам инцидент уже залогирован для разбора.
        }

        return ResponseEntity.ok().build();
    }
}