package com.printkiosk.shared.api.dto;

public record PriceBreakdown(
        int     pageCount,
        int     effectivePages,        // меньше pageCount если двусторонняя печать
        int     copies,
        int     pricePerPageSom,
        String  colorMode,             // "COLOR" | "BW"
        boolean doubleSided,
        int     totalSom
) {}