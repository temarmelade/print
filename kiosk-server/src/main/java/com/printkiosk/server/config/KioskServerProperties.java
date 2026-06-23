package com.printkiosk.server.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Strongly-typed wrapper for kiosk.* properties on the server side.
 * Covers PIN policy, file validation, and storage/public URL config.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "kiosk")
public class KioskServerProperties {

    /** PIN-код политика (TTL, попытки). */
    private Pin pin = new Pin();

    /** Валидация загружаемых файлов. */
    private FileConfig file = new FileConfig();

    /** Хранилище файлов (Docker volume). */
    private Storage storage = new Storage();

    @Getter
    @Setter
    public static class Pin {
        /** Сколько живёт PIN с момента генерации. */
        @NotNull
        private Duration ttl = Duration.ofMinutes(10);

        /** Максимум попыток ввода неверного PIN на стороне киоска. */
        @Positive
        private int maxAttempts = 5;

        /** Блокировка после превышения maxAttempts. */
        @NotNull
        private Duration lockout = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    public static class FileConfig {
        @Positive
        private long maxSizeBytes = 20_971_520L; // 20 MB

        @NotNull
        private List<String> allowedMime = List.of(
                "application/pdf",
                "image/jpeg",
                "image/png"
        );
    }

    @Getter
    @Setter
    public static class Storage {
        /** Путь к директории, куда сохраняются файлы (volume в Docker). */
        @NotBlank
        private String path = "/var/kiosk/uploads";

        /** Публичный base URL, через который Nginx отдаёт файлы. */
        @NotBlank
        private String publicBaseUrl = "https://kioskprint.kg";

        /** Как часто запускается очистка просроченных файлов. */
        @NotNull
        private Duration cleanupInterval = Duration.ofMinutes(1);

        /** Grace-период перед физическим удалением истёкшего файла. */
        @NotNull
        private Duration cleanupGrace = Duration.ofSeconds(30);
    }
}