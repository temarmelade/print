package com.printkiosk.client.api;

public class PinNotFoundException extends KioskServerException {
    public PinNotFoundException() {
        super("PIN_NOT_FOUND", "Код не найден или истёк");
    }
}
