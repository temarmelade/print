//package com.printkiosk.server.web;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.printkiosk.server.service.payment.FinikWebhookPayload;
//import com.printkiosk.server.service.payment.FinikWebhookVerifier;
//import com.printkiosk.server.service.payment.PaymentService;
//import com.printkiosk.shared.api.dto.PaymentSessionDto;
//import com.printkiosk.shared.api.dto.PaymentStatusDto;
//import jakarta.servlet.http.HttpServletRequest;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//@Slf4j
//@RestController
//@RequestMapping("/api/payments")
//@RequiredArgsConstructor
//public class PaymentController {
//
//    private final PaymentService paymentService;
//    private final FinikWebhookVerifier webhookVerifier;
//    private final ObjectMapper objectMapper;
//
//    /**
//     * Webhook от Finik о статусе платежа.
//     *
//     * Принимаем raw body (а не уже распарсенный DTO), потому что для верификации подписи
//     * нужен оригинальный JSON. Парсинг идёт уже после успешной проверки подписи.
//     */
//    @PostMapping("/finik/webhook")
//    public ResponseEntity<String> finikWebhook(
//            @RequestBody String rawBody,
//            HttpServletRequest request
//    ) {
//        if (!webhookVerifier.verify(request, rawBody)) {
//            log.warn(
//                    "Finik webhook REJECTED (invalid signature). Remote: {}",
//                    request.getRemoteAddr()
//            );
//            return ResponseEntity.status(403).body("Invalid signature");
//        }
//
//        try {
//            FinikWebhookPayload payload = objectMapper.readValue(rawBody, FinikWebhookPayload.class);
//            paymentService.applyWebhook(payload);
//            return ResponseEntity.ok("OK");
//        } catch (Exception e) {
//            log.error("Failed to handle Finik webhook (signature was valid)", e);
//            return ResponseEntity.status(400).body("Invalid payload");
//        }
//    }
//}