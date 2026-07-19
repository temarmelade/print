package com.printkiosk.client.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "kiosk.server")
public class ServerProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8080";

    /**
     * Публичный адрес сервера для ссылок в QR-кодах (скачивание сканов,
     * страница загрузки) — то, что открывает ТЕЛЕФОН пользователя. Отличается
     * от baseUrl: клиент ходит на сервер по localhost, а телефон — по сети.
     */
    @NotBlank
    private String publicBaseUrl = "http://localhost:8080";

    /** Идентификатор киоска, прокидывается в заголовке X-Kiosk-Id. */
    @NotBlank
    private String kioskId = "dev-kiosk";

    /**
     * Секретный ключ киоска (X-Kiosk-Key). Выдаётся один раз при регистрации
     * киоска в админке. Без него телеметрия не принимается.
     */
    private String apiKey = "";

    /** Токен для аутентификации (если когда-нибудь добавим). */
    private String authToken;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout    = Duration.ofSeconds(10);

    /** Таймаут для upload'а — должен быть больше, файл может быть большим. */
    private Duration uploadTimeout  = Duration.ofSeconds(60);
}