package com.printkiosk.server.exception;

/**
 * PIN валиден и файл активен, но уже закреплён за другим киоском
 * (held by another kiosk) и hold ещё не истёк. Маппится в HTTP 423 Locked.
 */
public class PinLockedByOtherKioskException extends RuntimeException {

    public PinLockedByOtherKioskException() {
        super("PIN is held by another kiosk");
    }
}