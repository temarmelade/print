package com.printkiosk.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Настройки админ-панели: подпись JWT, стартовый владелец, разрешённые CORS-источники.
 * Подхватывается через @ConfigurationPropertiesScan на пакете config.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "admin")
public class AdminProperties {

    private Jwt jwt = new Jwt();
    private Seed seed = new Seed();
    private Cors cors = new Cors();

    /**
     * Значение, которое лежало в открытом репозитории. Прод-профиль
     * отказывается стартовать с ним: подписать себе токен владельца
     * мог бы любой, кто видел исходники.
     */
    public static final String INSECURE_DEFAULT_SECRET =
            "change-me-in-prod-please-32chars-minimum-secret";

    /** Пароль стартового владельца из репозитория — на проде запрещён. */
    public static final String INSECURE_DEFAULT_PASSWORD = "owner12345";

    @Getter @Setter
    public static class Jwt {
        /** Секрет подписи HS256. МИНИМУМ 32 символа. На проде задать через ADMIN_JWT_SECRET. */
        private String secret = INSECURE_DEFAULT_SECRET;
        /** Срок жизни токена доступа. */
        private Duration ttl = Duration.ofHours(12);
    }

    @Getter @Setter
    public static class Seed {
        /** Логин первого владельца, создаётся при пустой таблице пользователей. */
        private String username = "owner";
        private String password = "owner12345";
        private String name = "Владелец";
    }

    @Getter @Setter
    public static class Cors {
        /** Источники, которым разрешён доступ к /api из браузера (админка). */
        private List<String> allowedOrigins = List.of("http://localhost:5174");
    }
}
