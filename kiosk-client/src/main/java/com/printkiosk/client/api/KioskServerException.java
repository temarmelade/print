package com.printkiosk.client.api;

/** Базовое исключение для всех HTTP-проблем при общении с сервером. */
public class KioskServerException extends RuntimeException {
    private final String code;          // ErrorResponse.code, либо "HTTP_xxx"

    public KioskServerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public KioskServerException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}

