package com.printkiosk.client.printer;

public class PrinterTimeoutException extends RuntimeException {
    public PrinterTimeoutException(String message) {
        super(message);
    }
}