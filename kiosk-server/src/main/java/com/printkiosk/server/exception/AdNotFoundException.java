package com.printkiosk.server.exception;

/** Рекламный креатив с указанным id не найден. Маппится в HTTP 404. */
public class AdNotFoundException extends RuntimeException {
    public AdNotFoundException() {
        super("Ad creative not found");
    }
}
