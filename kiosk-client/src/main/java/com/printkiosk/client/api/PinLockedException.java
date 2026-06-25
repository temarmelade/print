package com.printkiosk.client.api;

/**
 * Сервер вернул 423 Locked: PIN валиден, но удерживается другим киоском.
 * Юзеру показываем «код используется на другом терминале».
 */
public class PinLockedException extends KioskServerException {
    public PinLockedException() {
        super("PIN_LOCKED", "Этот код сейчас используется на другом терминале");
    }
}