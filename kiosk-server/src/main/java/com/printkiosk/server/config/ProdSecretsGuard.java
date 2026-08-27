package com.printkiosk.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Не даёт запустить прод с секретами из репозитория.
 *
 * <p>Репозиторий публичный, поэтому значения по умолчанию — это не
 * «слабые пароли», а «пароли, которые уже знают все». Мягкое
 * предупреждение в логе тут бесполезно: его никто не прочитает.
 * Поэтому приложение падает на старте с понятным списком того, что
 * нужно задать.
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProdSecretsGuard {

    private static final int MIN_SECRET_LENGTH = 32;

    private final AdminProperties admin;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        List<String> problems = new ArrayList<>();

        String secret = admin.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            problems.add("ADMIN_JWT_SECRET не задан");
        } else if (AdminProperties.INSECURE_DEFAULT_SECRET.equals(secret)) {
            problems.add("ADMIN_JWT_SECRET совпадает со значением из репозитория");
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            problems.add("ADMIN_JWT_SECRET короче " + MIN_SECRET_LENGTH + " символов");
        }

        if (AdminProperties.INSECURE_DEFAULT_PASSWORD.equals(admin.getSeed().getPassword())) {
            problems.add("ADMIN_OWNER_PASSWORD совпадает со значением из репозитория");
        }

        admin.getCors().getAllowedOrigins().stream()
                .filter(o -> o.contains("localhost") || o.equals("*"))
                .findAny()
                .ifPresent(o -> problems.add("ADMIN_CORS_ORIGINS содержит небезопасное значение: " + o));

        if (!problems.isEmpty()) {
            String message = "Небезопасная конфигурация прода:\n  - "
                    + String.join("\n  - ", problems);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Проверка секретов пройдена");
    }
}
