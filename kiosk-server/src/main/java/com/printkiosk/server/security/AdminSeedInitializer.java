package com.printkiosk.server.security;

import com.printkiosk.server.config.AdminProperties;
import com.printkiosk.server.domain.AdminUserRepository;
import com.printkiosk.server.service.AdminUserService;
import com.printkiosk.shared.api.AdminRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Создаёт первого владельца при пустой таблице пользователей, чтобы было чем
 * войти после чистого развёртывания. Логин/пароль — из admin.seed.* (env).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminSeedInitializer implements CommandLineRunner {

    private static final String DEFAULT_PASSWORD = "owner12345";

    private final AdminUserRepository repo;
    private final AdminUserService users;
    private final AdminProperties props;

    @Override
    public void run(String... args) {
        if (repo.count() > 0) return;

        var seed = props.getSeed();
        users.create(seed.getName(), seed.getUsername(), seed.getPassword(), AdminRole.OWNER);
        log.warn("СИД: создан владелец «{}» (логин: {}).", seed.getName(), seed.getUsername());
        if (DEFAULT_PASSWORD.equals(seed.getPassword())) {
            log.warn("СИД: используется пароль по умолчанию! Задайте ADMIN_OWNER_PASSWORD и смените его.");
        }
    }
}
