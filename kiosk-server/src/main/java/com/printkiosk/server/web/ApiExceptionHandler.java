package com.printkiosk.server.web;

import com.printkiosk.server.exception.FileValidationException;
import com.printkiosk.server.exception.JobNotFoundException;
import com.printkiosk.server.exception.PaymentGatewayException;
import com.printkiosk.server.exception.PinCollisionException;
import com.printkiosk.server.exception.PinLockedByOtherKioskException;
import com.printkiosk.server.exception.PinNotFoundException;
import com.printkiosk.shared.api.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import jakarta.validation.ConstraintViolationException;
import com.printkiosk.server.exception.AdNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PinNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(404)
                .body(new ErrorResponse("PIN_NOT_FOUND", "Код не найден или истёк"));
    }

    @ExceptionHandler(PinLockedByOtherKioskException.class)
    public ResponseEntity<ErrorResponse> lockedByOther() {
        return ResponseEntity.status(423)
                .body(new ErrorResponse("PIN_LOCKED",
                        "Этот код сейчас используется на другом терминале"));
    }

    @ExceptionHandler(PinCollisionException.class)
    public ResponseEntity<ErrorResponse> collision() {
        return ResponseEntity.status(503)
                .body(new ErrorResponse("PIN_SPACE_BUSY", "Слишком много активных кодов, повторите"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> badRequest() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "Неверный формат запроса"));
    }

    /**
     * Файл не прошёл валидацию по magic-bytes (пустой, повреждённый или
     * неподдерживаемый формат). Касается и веб-загрузки, и бота — отдаём
     * 400, чтобы клиент отличал «плохой файл» от сбоя сервера.
     */
    @ExceptionHandler(FileValidationException.class)
    public ResponseEntity<ErrorResponse> fileInvalid(FileValidationException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("FILE_INVALID", e.getMessage()));
    }

    /** Файл превысил multipart-лимит (20MB). Без этого Spring вернул бы 500. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> fileTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(new ErrorResponse("FILE_TOO_LARGE", "Файл слишком большой (макс. 20 МБ)"));
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ErrorResponse> jobNotFound(JobNotFoundException e) {
        return ResponseEntity.status(404)
                .body(new ErrorResponse("JOB_NOT_FOUND", "Заказ не найден"));
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ErrorResponse> paymentGateway(PaymentGatewayException e) {
        return ResponseEntity.status(503)
                .body(new ErrorResponse("PAYMENT_GATEWAY_DOWN", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> badState(IllegalStateException e) {
        return ResponseEntity.status(409)
                .body(new ErrorResponse("INVALID_STATE", e.getMessage()));
    }

    @ExceptionHandler(AdNotFoundException.class)
    public ResponseEntity<ErrorResponse> adNotFound() {
        return ResponseEntity.status(404)
                .body(new ErrorResponse("AD_NOT_FOUND", "Рекламный материал не найден"));
    }
}