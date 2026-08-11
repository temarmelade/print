package com.printkiosk.shared.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Новая цена печати. Верхняя граница — защита от опечатки в лишний ноль:
 * тариф в 100 000 сом за страницу заведомо ошибка ввода, а не бизнес-решение.
 */
public record UpdateTariffRequest(
        @Min(0) @Max(100_000) int bwPriceSom,
        @Min(0) @Max(100_000) int colorPriceSom
) {}
