package com.printkiosk.server.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.domain.AdminUser;
import com.printkiosk.server.domain.AdminUserRepository;
import com.printkiosk.server.exception.AdminRuleViolationException;
import com.printkiosk.server.exception.AdminUserNotFoundException;
import com.printkiosk.shared.api.AdminRole;
import com.printkiosk.shared.api.dto.AdminUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Аккаунты админ-панели: проверка входа и управление сотрудниками.
 * Бережём систему от самоблокировки — нельзя убрать последнего активного владельца.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MIN_PASSWORD_LEN = 8;

    private final AdminUserRepository repo;
    private final PasswordEncoder encoder;

    /** Проверка логина/пароля. Возвращает пользователя или бросает BadCredentials. */
    @Transactional(readOnly = true)
    public AdminUser authenticate(String username, String rawPassword) {
        AdminUser user = repo.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BadCredentialsException("Неверный логин или пароль"));
        if (!user.isEnabled() || !encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Неверный логин или пароль");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> list() {
        return repo.findAllByOrderByCreatedAtAsc().stream().map(AdminUserService::toDto).toList();
    }

    @Transactional
    public AdminUserDto create(String name, String username, String rawPassword, AdminRole role) {
        if (repo.existsByUsernameIgnoreCase(username)) {
            throw new AdminRuleViolationException("Логин уже занят");
        }
        validatePassword(rawPassword);
        AdminUser user = AdminUser.builder()
                .id(UuidCreator.getTimeOrderedEpoch())
                .name(name.trim())
                .username(username.trim())
                .passwordHash(encoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        log.info("Admin user created: username={}, role={}", user.getUsername(), role);
        return toDto(repo.save(user));
    }

    @Transactional
    public AdminUserDto setEnabled(UUID id, boolean enabled) {
        AdminUser user = getOrThrow(id);
        if (!enabled && user.getRole() == AdminRole.OWNER) ensureNotLastOwner(user);
        user.setEnabled(enabled);
        return toDto(user);
    }

    @Transactional
    public AdminUserDto changeRole(UUID id, AdminRole role) {
        AdminUser user = getOrThrow(id);
        if (user.getRole() == AdminRole.OWNER && role != AdminRole.OWNER) ensureNotLastOwner(user);
        user.setRole(role);
        return toDto(user);
    }

    @Transactional
    public void resetPassword(UUID id, String rawPassword) {
        validatePassword(rawPassword);
        getOrThrow(id).setPasswordHash(encoder.encode(rawPassword));
    }

    @Transactional
    public void delete(UUID id) {
        AdminUser user = getOrThrow(id);
        if (user.getRole() == AdminRole.OWNER) ensureNotLastOwner(user);
        repo.delete(user);
        log.info("Admin user deleted: username={}", user.getUsername());
    }

    // ── внутреннее ──

    private AdminUser getOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(AdminUserNotFoundException::new);
    }

    /** Защита от самоблокировки: последний активный владелец неприкосновенен. */
    private void ensureNotLastOwner(AdminUser owner) {
        if (owner.isEnabled() && repo.countByRoleAndEnabledTrue(AdminRole.OWNER) <= 1) {
            throw new AdminRuleViolationException("Нельзя убрать последнего активного владельца");
        }
    }

    private void validatePassword(String raw) {
        if (raw == null || raw.length() < MIN_PASSWORD_LEN) {
            throw new AdminRuleViolationException("Пароль должен быть не короче " + MIN_PASSWORD_LEN + " символов");
        }
    }

    public static AdminUserDto toDto(AdminUser u) {
        return new AdminUserDto(u.getId(), u.getName(), u.getUsername(),
                u.getRole(), u.isEnabled(), u.getCreatedAt());
    }
}
