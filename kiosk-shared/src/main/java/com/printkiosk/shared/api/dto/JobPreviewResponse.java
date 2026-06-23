package com.printkiosk.shared.api.dto;

import java.util.UUID;

public record JobPreviewResponse(
        UUID            fileId,
        String          originalFilename,
        PriceBreakdown  price
) {}
