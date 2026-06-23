package com.printkiosk.client.api;

public class ServerUnavailableException extends KioskServerException {
    public ServerUnavailableException(Throwable cause) {
        super("SERVER_UNAVAILABLE", "Сервер недоступен", cause);
    }
}