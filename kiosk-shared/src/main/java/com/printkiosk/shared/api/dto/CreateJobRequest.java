package com.printkiosk.shared.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateJobRequest(
        @NotNull @Pattern(regexp = "\\d{4}") String pin,
        @NotNull @Valid PrintSettings settings,
        /** Выбранные страницы (1-based). null или пусто — печатать/считать все. */
        List<Integer> pages
) {}