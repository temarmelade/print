package com.printkiosk.shared.api.dto;

/** Ответ на вход: токен доступа + профиль вошедшего. */
public record LoginResponse(
        String token,
        AdminUserDto user
) {}
