package com.printkiosk.shared.api.dto;

import java.util.UUID;

public record PaymentStatusDto(
        UUID jobId,
        String status,
        String paymentUrl
) {
    public boolean isPaid() {
        return "PAID".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status);
    }
    public static PaymentStatusDto notFound() {
        return new PaymentStatusDto(null, "NOT_FOUND", null);
    }
}
