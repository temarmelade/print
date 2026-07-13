package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.PrintJobStatus;

import java.time.Instant;
import java.util.UUID;

/** Строка таблицы транзакций (задание + его оплата). */
public record TransactionDto(
        UUID id,
        String pin,                 // код файла — по нему поддержка ищет обращение
        String fileName,
        int pageCount,
        int copies,
        String colorMode,
        int priceSom,
        PrintJobStatus status,
        String paymentStatus,       // PENDING / PAID / FAILED / CANCELLED / null
        String paymentId,
        String kioskId,
        Instant createdAt,
        Instant paidAt,
        Instant completedAt
) {}
