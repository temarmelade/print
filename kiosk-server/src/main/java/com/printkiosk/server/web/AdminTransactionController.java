package com.printkiosk.server.web;

import com.printkiosk.server.service.TransactionService;
import com.printkiosk.shared.api.OperationType;
import com.printkiosk.shared.api.PrintJobStatus;
import com.printkiosk.shared.api.dto.TransactionPageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Транзакции для админ-панели. Доступ: владелец и поддержка
 * (техник финансы не видит — см. карту доступов в SPA).
 *
 *   GET /api/admin/transactions?from&to&status&paymentStatus&kioskId&q&page&size
 *   GET /api/admin/transactions/kiosks
 */
@RestController
@RequestMapping("/api/admin/transactions")
@PreAuthorize("hasAnyRole('OWNER','SUPPORT')")
@RequiredArgsConstructor
public class AdminTransactionController {

    private final TransactionService transactions;

    @GetMapping
    public TransactionPageDto list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) PrintJobStatus status,
            @RequestParam(required = false) OperationType operationType,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String kioskId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return transactions.search(from, to, status, operationType, paymentStatus, kioskId, q, page, size);
    }

    @GetMapping("/kiosks")
    public List<String> kiosks() {
        return transactions.kiosks();
    }
}
