package com.printkiosk.shared.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentEventDto(
        String  pin,
        UUID    jobId,
        String  type,           // PAID | FAILED | CANCELLED | EXPIRED
        Instant timestamp
) {}
