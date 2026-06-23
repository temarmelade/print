package com.printkiosk.shared.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PrintSettings(
        @Min(1) @Max(100) int copies,
        @NotBlank String colorMode,     // BW | COLOR (когда сделаешь enum — замени)
        boolean doubleSided,
        @NotBlank String orientation,   // PORTRAIT | LANDSCAPE
        @NotBlank String paperSize      // A4 | A3
) {}