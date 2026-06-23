package com.printkiosk.shared.api.dto;

import java.time.Instant;

public record UploadResponse(
        String  pin,
        Instant expiresAt,
        long    ttlSeconds
) {}
