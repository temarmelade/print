package com.printkiosk.server.exception;

/**
 * Документ Word не удалось превратить в PDF: LibreOffice не установлен,
 * не уложился в таймаут или не смог открыть файл.
 *
 * <p>Отдельно от {@link FileValidationException}: файл при этом может быть
 * совершенно нормальным, и сообщать посетителю «файл повреждён» — неправда.
 */
public class DocumentConversionException extends RuntimeException {
    public DocumentConversionException(String message) {
        super(message);
    }

    public DocumentConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
