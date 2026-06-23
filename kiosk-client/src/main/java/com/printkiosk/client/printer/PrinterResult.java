package com.printkiosk.client.printer;

public record PrinterResult(boolean success, String errorMessage) {
    public static PrinterResult completed() {
        return new PrinterResult(true, null);
    }
    public static PrinterResult failed(String message) {
        return new PrinterResult(false, message);
    }
}