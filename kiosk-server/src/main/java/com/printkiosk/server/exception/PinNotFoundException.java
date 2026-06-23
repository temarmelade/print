package com.printkiosk.server.exception;

public class PinNotFoundException extends RuntimeException {
    public PinNotFoundException(String message) {
        super(message);
    }
    public PinNotFoundException() {
        super("PIN not found or expired");
    }
}
