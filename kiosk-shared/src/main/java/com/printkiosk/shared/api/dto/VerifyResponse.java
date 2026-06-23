package com.printkiosk.shared.api.dto;

import java.time.Instant;
import java.util.UUID;

public record VerifyResponse(
        UUID id,
        String  downloadUrl,
        String  originalFilename,
        String  contentType,
        long    fileSize,
        Instant expiresAt
) {}
