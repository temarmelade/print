package com.printkiosk.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.printkiosk.server.service.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Приём уведомлений об оплате от Bakai.
 *
 * <p>Адрес этого метода указывается в поле «получать уведомления» при
 * создании внешнего сервиса в Bakai Business:
 * {@code https://ваш-домен/api/payments/webhook/bakai}
 *
 * <p>Точный формат тела на момент написания неизвестен, поэтому разбор
 * намеренно терпимый: проверяется несколько вероятных имён полей, а всё
 * тело целиком пишется в лог. По первому реальному уведомлению станет
 * видно фактическую структуру, и разбор можно будет сузить.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class BakaiWebhookController {

    /** Где может лежать наш operationID. */
    private static final List<String> ORDER_ID_FIELDS =
            List.of("operationID", "operationId", "transactionID", "transactionId", "orderId");

    /** Где может лежать результат. */
    private static final List<String> STATUS_FIELDS =
            List.of("state", "status", "result", "paymentStatus");

    private static final List<String> SUCCESS_VALUES =
            List.of("success", "succeeded", "paid", "completed", "ok", "true");

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @PostMapping("/bakai")
    public ResponseEntity<Void> handle(@RequestBody String rawBody) {
        // Полное тело в лог: пока формат не подтверждён, это единственный
        // способ увидеть, что именно присылает банк.
        log.info("Уведомление Bakai: {}", rawBody);

        try {
            JsonNode root = objectMapper.readTree(rawBody);

            String orderId = firstText(root, ORDER_ID_FIELDS);
            String status  = firstText(root, STATUS_FIELDS);

            if (orderId == null) {
                log.error("В уведомлении Bakai нет идентификатора операции. "
                        + "Проверьте поля в теле выше и поправьте ORDER_ID_FIELDS.");
                return ResponseEntity.ok().build();
            }

            boolean success = status != null
                    && SUCCESS_VALUES.contains(status.trim().toLowerCase());

            if (status == null) {
                // Перечисляем реальные поля: по ним сразу видно, какое
                // имя добавить в STATUS_FIELDS, не разбирая тело руками.
                log.warn("Статус не найден. Поля верхнего уровня: {}. "
                                + "Оплату считаем неуспешной, чтобы не напечатать бесплатно.",
                        fieldNames(root));
            } else if (!success) {
                log.warn("Статус '{}' не распознан как успешный. Известные значения: {}",
                        status, SUCCESS_VALUES);
            }

            log.info("Разбор уведомления: orderId={} статус={} успех={}",
                    orderId, status, success);

            paymentService.handleBakaiCallback(orderId, success);

        } catch (Exception e) {
            // 200 в любом случае: иначе банк будет повторять уведомление
            // бесконечно. Ошибка уже в логе и разбирается вручную.
            log.error("Не удалось обработать уведомление Bakai", e);
        }

        return ResponseEntity.ok().build();
    }

    /** Имена полей верхнего уровня — подсказка при незнакомом формате. */
    private static String fieldNames(JsonNode root) {
        List<String> names = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "<нет>" : String.join(", ", names);
    }

    /** Первое непустое строковое значение из перечисленных полей, включая вложенные. */
    private static String firstText(JsonNode root, List<String> names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
            // Часть провайдеров заворачивает полезную нагрузку в data/payload.
            for (String wrapper : List.of("data", "payload", "result")) {
                JsonNode nested = root.path(wrapper).get(name);
                if (nested != null && !nested.isNull() && !nested.asText().isBlank()) {
                    return nested.asText();
                }
            }
        }
        return null;
    }
}