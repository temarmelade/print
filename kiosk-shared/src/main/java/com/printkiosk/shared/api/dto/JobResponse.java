package com.printkiosk.shared.api.dto;

 // ⚠ см. пояснение ниже
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID    id,
        UUID    fileId,
        String  status,
        int     priceSom,
        String  paymentUrl,
        Instant createdAt
) { }