package com.printkiosk.server.exception;

public class PinCollisionException extends RuntimeException {
    public PinCollisionException(String message) {
        super(message);
    }
    public PinCollisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
