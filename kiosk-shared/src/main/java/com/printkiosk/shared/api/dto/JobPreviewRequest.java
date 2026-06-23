package com.printkiosk.shared.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record JobPreviewRequest(
        @NotNull @Pattern(regexp = "\\d{4}") String pin,
        @NotNull @Valid PrintSettings settings
) {}
