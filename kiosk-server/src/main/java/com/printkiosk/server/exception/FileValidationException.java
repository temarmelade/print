package com.printkiosk.server.exception;

import com.printkiosk.server.service.FileValidationService;

public class FileValidationException extends RuntimeException {
    public FileValidationException(String message) {
        super(message);
    }

    public FileValidationException(FileValidationService.Reason reason) {
        super(reason.toString());
    }
}
