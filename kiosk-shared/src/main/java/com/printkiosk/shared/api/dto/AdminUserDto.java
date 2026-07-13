package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.AdminRole;

import java.time.Instant;
import java.util.UUID;

/** Публичное представление аккаунта админки (без пароля). */
public record AdminUserDto(
        UUID id,
        String name,
        String username,
        AdminRole role,
        boolean enabled,
        Instant createdAt
) {}
