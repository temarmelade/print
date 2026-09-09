package com.printkiosk.server.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.printkiosk.server.config.BakaiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Платёжный шлюз Bakai OpenBanking.
 *
 * <h2>Чем отличается от Finik</h2>
 * <ul>
 *   <li>Авторизация — JWT по логину/паролю вместо подписи запроса RSA.</li>
 *   <li>Банк сам рисует QR и возвращает готовую картинку — киоску не нужно
 *       генерировать код из ссылки.</li>
 *   <li><b>Вебхуков нет.</b> Статус узнаётся только опросом, этим занят
 *       {@link BakaiPaymentPoller}.</li>
 * </ul>
 *
 * <p>{@code @Primary} делает этот бин победителем при инъекции
 * {@link PaymentGateway}, пока включён {@code bakai.enabled}. Старый
 * {@link FinikPaymentGateway} остаётся в коде и поднимается, если Bakai
 * выключить, — откат к прежнему провайдеру не требует пересборки.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "bakai", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class BakaiPaymentGateway implements PaymentGateway {

    /**
     * Метод генерации QR. Токен Bakai выдаётся ПОД КОНКРЕТНУЮ УСЛУГУ,
     * поэтому вызывать надо ровно тот метод, под который создан внешний
     * сервис: обращение к соседнему возвращает 401.
     *
     * <p>Путь вынесен в настройку, чтобы переключаться без пересборки,
     * если услугу заведут другую.
     */
    private String generateQrPath() {
        return props.getGenerateQrPath();
    }

    private final BakaiProperties props;
    private final BakaiTokenProvider tokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public GatewayPaymentResult createPayment(String orderId, int amountSom) {
        if (amountSom <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than 0");
        }

        try {
            // operationID = наш orderId ("PIN-1234"). Он же уйдёт в
            // transactionID при запросе статуса — единственная ниточка,
            // по которой ответ банка сопоставляется с заданием печати.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accountNo", props.getAccountNo());
            body.put("currencyId", props.getCurrencyId());
            body.put("amount", amountSom);
            body.put("operationID", orderId);
            // comment принимает только GenerateQRWithComment. Для обычного
            // GenerateQR лишнее поле не нужно — отправляем его лишь когда
            // вызываем соответствующий метод.
            if (generateQrPath().endsWith("WithComment")) {
                body.put("comment", "Оплата печати " + orderId);
            }
            // qrTtlUnits — числовой enum (1 = Minutes), не строка.
            body.put("qrTtlUnits", props.getQrTtlUnits());
            body.put("qrTtl", props.getQrTtl());

            String payload = mapper.writeValueAsString(body);
            log.info("Bakai {} → {}", generateQrPath(), payload);

            JsonNode json = post(generateQrPath(), payload);

            String qrLink = json.path("qrLink").asText(null);
            if (qrLink == null || qrLink.isBlank()) {
                throw new IllegalStateException("Bakai вернул пустой qrLink");
            }

            // Готовая картинка от банка. Именно её показываем на киоске:
            // если перерисовать QR по одной лишь ссылке, банковское
            // приложение может не распознать payload — оно ждёт формат
            // платёжного QR, а не произвольный URL.
            String qrImage = json.path("qrImage").asText(null);

            log.info("Bakai QR created: orderId={} amount={} (картинка от банка: {})",
                    orderId, amountSom, qrImage != null ? "есть" : "нет");

            // paymentId = orderId осознанно: у Bakai нет собственного
            // идентификатора платежа в ответе, а статус запрашивается
            // именно по нашему operationID.
            return new GatewayPaymentResult(orderId, qrLink, amountSom, "PENDING", qrImage);

        } catch (Exception e) {
            log.error("Не удалось создать QR в Bakai для orderId={}", orderId, e);
            throw new RuntimeException("Не удалось создать оплату Bakai: " + e.getMessage(), e);
        }
    }

    /**
     * Статус платежа по нашему operationID.
     *
     * @return {@code Success} | {@code Error} | {@code Processed}, либо null,
     *         если банк не ответил внятно — тогда опрос просто продолжится
     */
    public String fetchState(String orderId) {
        try {
            String uri = props.getBaseUrl() + "/api/Qr/GetStateCustomQr"
                    + "?qrType=" + enc(props.getQrType())
                    + "&transactionID=" + enc(orderId);

            JsonNode json = get(uri);
            String state = json.path("state").asText(null);

            if ("Error".equalsIgnoreCase(state)) {
                log.warn("Bakai вернул ошибку по {}: {}", orderId,
                        json.path("errorMessage").asText(""));
            }
            return state;

        } catch (Exception e) {
            // Сеть моргнула или банк недоступен — платёж неуспешным НЕ
            // считаем, попробуем в следующем цикле.
            log.warn("Не удалось получить статус Bakai для {}: {}", orderId, e.getMessage());
            return null;
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = send(() -> HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Content-Type", "application/json")
                // urgent=true: платёж создаётся, когда человек уже стоит у
                // терминала. Пауза после неудачи фонового опроса не должна
                // мешать ему оплатить.
                .header("Authorization", "Bearer " + tokens.token(true))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        return parse(response);
    }

    private JsonNode get(String uri) throws Exception {
        HttpResponse<String> response = send(() -> HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + tokens.token())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
        return parse(response);
    }

    /**
     * Один повтор при 401: токен мог протухнуть между проверкой и отправкой.
     * Без этого первый платёж после истечения срока падал бы всегда.
     */
    private HttpResponse<String> send(RequestFactory factory) throws Exception {
        HttpResponse<String> response = http.send(
                factory.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // Повтор имеет смысл только если токен можно перевыпустить. При
        // заданном вручную BAKAI_TOKEN обновлять нечего — второй запрос
        // уйдёт с тем же токеном и получит тот же 401, удвоив нагрузку и
        // запутав лог.
        boolean manualToken = props.getToken() != null && !props.getToken().isBlank();

        if (response.statusCode() == 401 && !manualToken) {
            log.info("Bakai вернул 401 — обновляем токен и повторяем запрос");
            tokens.refresh();
            response = http.send(
                    factory.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } else if (response.statusCode() == 401) {
            log.error("Bakai отклонил заданный вручную токен (BAKAI_TOKEN). "
                    + "Проверьте, что внешний сервис подтверждён в Bakai Business "
                    + "и что токен не истёк.");
        }
        return response;
    }

    private JsonNode parse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Bakai ответил " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());

        // Часть методов заворачивает полезную нагрузку в BaseResponse
        // {responseMessage, statusCode, result} — разворачиваем, чтобы
        // вызывающий код не разбирался в двух форматах ответа.
        if (json.has("statusCode") && json.has("result")) {
            int code = json.path("statusCode").asInt();
            if (code != 200) {
                throw new IllegalStateException("Bakai statusCode=" + code
                        + ": " + json.path("responseMessage").asText(""));
            }
            return json.path("result");
        }
        return json;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(
                value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest build();
    }
}