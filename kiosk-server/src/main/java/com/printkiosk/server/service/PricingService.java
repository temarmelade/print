package com.printkiosk.server.service;

import com.printkiosk.server.domain.TariffEntity;
import com.printkiosk.shared.api.dto.PriceBreakdown;
import com.printkiosk.shared.api.dto.PrintSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Считает цену задания. Источник тарифа — {@link TariffService},
 * который читает БД. Никаких хардкодных констант.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final TariffService tariffService;

    /**
     * Полный расчёт с разбивкой — клиент показывает на экране SUMMARY,
     * чтобы юзер видел, из чего складывается сумма.
     */
    public PriceBreakdown calculate(int pageCount, PrintSettings settings, String kioskId) {
        TariffEntity tariff = tariffService.getCurrentFor(kioskId);

        boolean isColor = "COLOR".equalsIgnoreCase(settings.colorMode());
        int perPage = isColor ? tariff.getColorPriceSom() : tariff.getBwPriceSom();

        int effectivePages = settings.doubleSided()
                ? (pageCount + 1) / 2
                : pageCount;

        int totalSom = effectivePages * settings.copies() * perPage;

        return new PriceBreakdown(
                pageCount,
                effectivePages,
                settings.copies(),
                perPage,
                isColor ? "COLOR" : "BW",
                settings.doubleSided(),
                totalSom
        );
    }

    /** Только итоговую сумму — используется при создании job'а. */
    public int calculateTotal(int pageCount, PrintSettings settings, String kioskId) {
        return calculate(pageCount, settings, kioskId).totalSom();
    }
}