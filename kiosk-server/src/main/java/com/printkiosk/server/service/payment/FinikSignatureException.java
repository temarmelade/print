package com.printkiosk.server.service.payment;

class FinikSignatureException extends RuntimeException {
    FinikSignatureException(String message) {
        super(message);
    }
    FinikSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}