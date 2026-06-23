package com.printkiosk.server.service.payment;

public record GatewayPaymentResult(
        String paymentId,
        String paymentUrl,
        int    amount,
        String status
) {}