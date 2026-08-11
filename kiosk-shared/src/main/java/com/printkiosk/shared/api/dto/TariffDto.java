package com.printkiosk.shared.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Тариф киоска. {@code kioskId == null} — глобальный дефолт, который
 * действует для всех киосков без собственной цены.
 *
 * @param kioskName подставляется сервером для удобства админки; null для
 *                  глобального тарифа и для киосков, удалённых из сети
 * @param effectiveTo null у действующего тарифа, дата закрытия — у архивного
 */
public record TariffDto(
        UUID id,
        String kioskId,
        String kioskName,
        int bwPriceSom,
        int colorPriceSom,
        Instant effectiveFrom,
        Instant effectiveTo
) {}
