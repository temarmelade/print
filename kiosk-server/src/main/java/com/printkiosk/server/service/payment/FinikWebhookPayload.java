package com.printkiosk.server.service.payment;

import java.util.Map;

public record FinikWebhookPayload(
        String id,
        String transactionId,
        String status,
        int amount,
        int net,
        String accountId,
        Map<String, Object> fields,
        Map<String, Object> data,
        Long requestDate,
        Long transactionDate,
        String transactionType,
        String receiptNumber
) {}