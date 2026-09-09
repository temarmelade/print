package com.printkiosk.server.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.printkiosk.server.config.BakaiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Хранит и обновляет JWT для Bakai.
 *
 * <p>Токен запрашивается один раз и переиспользуется: логиниться на каждый
 * платёж — лишний round-trip в момент, когда человек стоит у терминала.
 *
 * <p>Срок жизни в документации банка не указан, поэтому он читается из
 * самого токена (claim {@code exp}). Если разобрать не удалось,
 * используется осторожный запас в 5 минут — лучше лишний раз
 * перелогиниться, чем словить 401 посреди оплаты.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BakaiTokenProvider {

    private static final Duration FALLBACK_TTL = Duration.ofMinutes(5);

    private final BakaiProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    /**
     * Не пытаться логиниться чаще, чем раз в этот срок, после неудачи.
     * Опрос платежей идёт каждые 3 секунды, и без паузы неверные учётные
     * данные приводят к сотням запросов в минуту и стене стектрейсов.
     */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(30);
    private volatile Instant nextAttemptAt = Instant.EPOCH;

    /**
     * Токен для фоновых задач. После неудачного входа выдержит паузу,
     * чтобы опрос платежей не долбил банк каждые 3 секунды.
     */
    public synchronized String token() {
        return token(false);
    }

    /**
     * @param urgent true — попытаться войти, даже если недавно была
     *               неудача. Так вызывает создание платежа: у терминала
     *               стоит человек, и отказывать ему из-за паузы,
     *               выставленной фоновым опросом, недопустимо.
     */
    public synchronized String token(boolean urgent) {
        // Токен задан вручную — логин не трогаем вовсе. Это единственный
        // рабочий путь, если учётные данные из PDF одноразовые: второй
        // вызов /Auth/Login с ними вернёт 400.
        if (props.getToken() != null && !props.getToken().isBlank()) {
            return props.getToken().trim();
        }

        Instant threshold = Instant.now().plusSeconds(props.getTokenRefreshSkewSec());
        if (token != null && expiresAt.isAfter(threshold)) {
            return token;
        }
        return login(urgent);
    }

    /** Принудительный перелогин — вызывается при 401 от любого метода. */
    public synchronized String refresh() {
        if (props.getToken() != null && !props.getToken().isBlank()) {
            // Обновить нечего: токен задан руками. Если банк ответил 401,
            // значит он протух и его нужно перевыпустить вручную.
            log.warn("Bakai вернул 401, а токен задан вручную — "
                   + "получите новый и обновите BAKAI_TOKEN");
            return props.getToken().trim();
        }
        token = null;
        return login(true);
    }

    private String login(boolean urgent) {
        if (!urgent && Instant.now().isBefore(nextAttemptAt)) {
            throw new IllegalStateException(
                    "Авторизация в Bakai недавно не удалась — ждём до " + nextAttemptAt);
        }

        // Проверяем ДО запроса: с пустыми полями банк вернёт 400, и по
        // логу будет непонятно, дело в учётных данных или в самом сервисе.
        if (props.getLogin() == null || props.getLogin().isBlank()
                || props.getPassword() == null || props.getPassword().isBlank()) {
            throw new IllegalStateException(
                    "Не заданы BAKAI_LOGIN / BAKAI_PASSWORD — авторизация невозможна");
        }

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "login", props.getLogin(),
                    "password", props.getPassword()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/Auth/Login"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                // Тело ответа обязательно: банк объясняет в нём причину,
                // а без этого 400 неотличим от неверного логина, кривого
                // формата запроса и неподтверждённого внешнего сервиса.
                throw new IllegalStateException("Bakai login failed, status "
                        + response.statusCode() + ", ответ: " + shorten(response.body()));
            }

            JsonNode json = mapper.readTree(response.body());
            String fresh = json.path("token").asText(null);
            if (fresh == null || fresh.isBlank()) {
                throw new IllegalStateException("Bakai login returned empty token");
            }

            token = fresh;
            expiresAt = readExpiry(fresh);
            nextAttemptAt = Instant.EPOCH;   // связь есть, пауза больше не нужна
            log.info("Bakai token obtained, expires at {}", expiresAt);
            return token;

        } catch (Exception e) {
            nextAttemptAt = Instant.now().plus(RETRY_AFTER_FAILURE);
            // Без стектрейса: причина уже в сообщении, а полотно на каждый
            // цикл опроса делает лог нечитаемым.
            log.error("Авторизация в Bakai не удалась: {}. Повтор через {} с",
                    e.getMessage(), RETRY_AFTER_FAILURE.toSeconds());
            throw new IllegalStateException("Не удалось авторизоваться в Bakai: " + e.getMessage(), e);
        }
    }

    /** Обрезает тело ответа: полный HTML-ответ в логе бесполезен. */
    private static String shorten(String body) {
        if (body == null) return "<пусто>";
        String trimmed = body.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }

    /** Достаёт {@code exp} из payload JWT без верификации подписи. */
    private Instant readExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
                JsonNode node = mapper.readTree(payload);
                if (node.hasNonNull("exp")) {
                    return Instant.ofEpochSecond(node.get("exp").asLong());
                }
            }
        } catch (Exception e) {
            log.debug("Не удалось прочитать exp из токена Bakai, берём запас по умолчанию");
        }
        return Instant.now().plus(FALLBACK_TTL);
    }
}
