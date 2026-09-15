package com.printkiosk.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printkiosk.server.service.payment.PaymentService;
import com.printkiosk.shared.api.dto.BakaiCallbackPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Приём уведомлений об оплате от Bakai.
 *
 * <p>Адрес указывается в поле «получать уведомления» при создании
 * внешнего сервиса в Bakai Business:
 * {@code https://ваш-домен/api/payments/webhook/bakai}
 *
 * <p>Подписи у уведомления нет, поэтому одного факта запроса мало:
 * подтверждение принимается, только если сумма совпадает со стоимостью
 * задания, а сам operationID существует в базе. Это проверяет
 * {@link PaymentService#handleBakaiCallback}.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class BakaiWebhookController {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @PostMapping("/bakai")
    public ResponseEntity<Void> handle(@RequestBody String rawBody) {
        // Тело целиком в лог: единственный способ разобраться, если банк
        // once поменяет формат — по коду этого не увидеть.
        log.info("Уведомление Bakai: {}", rawBody);

        try {
            BakaiCallbackPayload payload =
                    objectMapper.readValue(rawBody, BakaiCallbackPayload.class);

            if (payload.operationID() == null || payload.operationID().isBlank()) {
                log.error("В уведомлении нет operationID — сопоставить с заданием нельзя");
                return ResponseEntity.ok().build();
            }

            paymentService.handleBakaiCallback(
                    payload.operationID(),
                    payload.isSuccess(),
                    payload.amount(),
                    payload.qrTransactionID());

        } catch (Exception e) {
            // 200 в любом случае: иначе банк будет повторять уведомление
            // бесконечно, а разбирать всё равно придётся по логу.
            log.error("Не удалось обработать уведомление Bakai", e);
        }

        return ResponseEntity.ok().build();
    }
}
