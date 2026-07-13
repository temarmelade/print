package com.printkiosk.server.security;

import com.printkiosk.shared.api.AdminRole;

import java.util.UUID;

/** Принципал вошедшего сотрудника — кладётся в SecurityContext из JWT. */
public record AdminPrincipal(UUID id, String username, String name, AdminRole role) {}
