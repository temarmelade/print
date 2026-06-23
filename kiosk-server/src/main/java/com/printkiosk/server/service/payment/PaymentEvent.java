package com.printkiosk.server.service.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие смены статуса платежа. Шлётся в SSE-эндпоинт и оттуда — клиенту.
 */
public record PaymentEvent(
        String  pin,
        UUID    jobId,
        Type    type,
        Instant timestamp
) {
    public enum Type {
        PAID,            // оплата подтверждена
        FAILED,          // ошибка платежа от шлюза
        CANCELLED,       // юзер отменил (или Finik отменил по таймауту)
        EXPIRED          // наш TTL истёк
    }

    public static PaymentEvent paid(String pin, UUID jobId) {
        return new PaymentEvent(pin, jobId, Type.PAID, Instant.now());
    }
    public static PaymentEvent failed(String pin, UUID jobId) {
        return new PaymentEvent(pin, jobId, Type.FAILED, Instant.now());
    }
    public static PaymentEvent cancelled(String pin, UUID jobId) {
        return new PaymentEvent(pin, jobId, Type.CANCELLED, Instant.now());
    }
    public static PaymentEvent expired(String pin, UUID jobId) {
        return new PaymentEvent(pin, jobId, Type.EXPIRED, Instant.now());
    }
}