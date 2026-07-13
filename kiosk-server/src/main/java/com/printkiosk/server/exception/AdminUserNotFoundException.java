package com.printkiosk.server.exception;

/** Аккаунт админки с указанным id не найден. HTTP 404. */
public class AdminUserNotFoundException extends RuntimeException {
    public AdminUserNotFoundException() {
        super("Admin user not found");
    }
}
