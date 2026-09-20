package com.printkiosk.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

    /** Тарификация цифровой доставки отсканированных документов. */
    private ScanDelivery scanDelivery = new ScanDelivery();

    @Valid
    private Conversion conversion = new Conversion();

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

        /**
         * Публичный base URL сервера: через него Nginx отдаёт файлы, и из
         * него же строятся ссылки в QR-кодах для телефона. localhost здесь
         * допустим только локально — тогда ссылки для телефона строятся по
         * IP машины в сети (см. {@link PublicUrlResolver}).
         */
        @NotBlank
        private String publicBaseUrl = "http://localhost:8080";

        /** Как часто запускается очистка просроченных файлов. */
        @NotNull
        private Duration cleanupInterval = Duration.ofMinutes(1);

        /** Grace-период перед физическим удалением истёкшего файла. */
        @NotNull
        private Duration cleanupGrace = Duration.ofSeconds(30);
    }

    /** Конвертация DOC/DOCX → PDF через LibreOffice. */
    @Getter
    @Setter
    public static class Conversion {
        /**
         * Путь к soffice. Пусто — ищем сами: стандартные пути Linux/Windows/
         * macOS, затем PATH. В Docker-образе LibreOffice ставится в
         * /usr/bin/soffice и находится без настройки.
         */
        private String sofficePath = "";

        /** Предел на одну конвертацию; зависший процесс убивается. */
        @NotNull
        private java.time.Duration timeout = java.time.Duration.ofSeconds(90);

        /**
         * Сколько конвертаций идёт одновременно. Каждая — отдельный процесс
         * LibreOffice (150–300 МБ) со своим профилем: с общим профилем
         * параллельные запуски молча теряют результат.
         */
        @Positive
        private int maxParallel = 2;

        /** Папка профилей LibreOffice. Пусто — {java.io.tmpdir}/kiosk-lo. */
        private String workDir = "";
    }

    @Getter
    @Setter
    public static class ScanDelivery {
        /**
         * Плата за одну страницу за цифровую доставку отсканированного
         * документа (получение через сайт или Telegram). Печать сканов от
         * этой платы не зависит и идёт обычным трактом печати.
         */
        @PositiveOrZero
        private int pricePerPageSom = 10;

        /**
         * Сколько скан доступен для получения ПОСЛЕ оплаты доставки. Обычный
         * срок жизни файла (kiosk.pin.ttl, 10 мин) отсчитывается от загрузки,
         * а оплата сама занимает до 5 минут — на скачивание оставались
         * считаные минуты. После оплаты срок продлевается до этого значения.
         */
        @NotNull
        private java.time.Duration downloadTtl = java.time.Duration.ofMinutes(30);
    }
}