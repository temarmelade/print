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

    /** Действующий токен; при необходимости логинится заново. */
    public synchronized String token() {
        Instant threshold = Instant.now().plusSeconds(props.getTokenRefreshSkewSec());
        if (token != null && expiresAt.isAfter(threshold)) {
            return token;
        }
        return login();
    }

    /** Принудительный перелогин — вызывается при 401 от любого метода. */
    public synchronized String refresh() {
        token = null;
        return login();
    }

    private String login() {
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
                throw new IllegalStateException(
                        "Bakai login failed, status " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            String fresh = json.path("token").asText(null);
            if (fresh == null || fresh.isBlank()) {
                throw new IllegalStateException("Bakai login returned empty token");
            }

            token = fresh;
            expiresAt = readExpiry(fresh);
            log.info("Bakai token obtained, expires at {}", expiresAt);
            return token;

        } catch (Exception e) {
            log.error("Bakai login failed", e);
            throw new IllegalStateException("Не удалось авторизоваться в Bakai: " + e.getMessage(), e);
        }
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
