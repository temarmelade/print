package com.printkiosk.shared.api.dto;

import java.util.UUID;

public record PaymentSessionDto(
        UUID    jobId,
        String  paymentId,
        String  paymentUrl,
        int     priceSom
) {}
