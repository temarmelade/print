package com.printkiosk.server.web;

import com.printkiosk.server.service.payment.PaymentService;
import com.printkiosk.shared.api.dto.PaymentSessionDto;
import com.printkiosk.shared.api.dto.PaymentStatusDto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Validated
public class PaymentApiController {

    private final PaymentService paymentService;

    /** Создать платёжную сессию для READY-job'а. Идемпотентно по jobId. */
    @PostMapping
    public ResponseEntity<PaymentSessionDto> create(@RequestBody CreateSessionRequest req) {
        return ResponseEntity.ok(paymentService.createSession(req.jobId()));
    }

    /** Polling статуса оплаты по PIN. */
    @GetMapping("/{pin}/status")
    public ResponseEntity<PaymentStatusDto> status(
            @PathVariable("pin") @Pattern(regexp = "\\d{4}") String pin) {
        return ResponseEntity.ok(paymentService.getStatusByPin(pin));
    }

    public record CreateSessionRequest(@NotNull UUID jobId) {}
}

