package com.printkiosk.shared.api.dto;

import java.time.Instant;

/**
 * Прогноз, когда на киоске закончатся расходники.
 *
 * <p>Считается по накопленной истории телеметрии: темп печати (страниц в
 * сутки) × остаток. Как и везде в телеметрии, {@code null} означает «пока
 * не знаем», а не «скоро». Прогноз без данных хуже отсутствия прогноза:
 * техник поедет не туда.
 */
public record SupplyForecastDto(
        String kioskId,

        /** Страниц в сутки по последним наблюдениям. null — данных мало. */
        Double pagesPerDay,

        /** Когда закончится бумага. null — неизвестно или расход нулевой. */
        Instant paperEmptyAt,
        Integer paperDaysLeft,

        /** Когда закончится тонер. */
        Instant tonerEmptyAt,
        Integer tonerDaysLeft,

        /** На скольких точках истории построен прогноз — мера доверия. */
        int samples,

        /** Часов между первой и последней точкой. */
        long windowHours
) {
    /** Есть ли что показывать пользователю. */
    public boolean hasData() {
        return pagesPerDay != null;
    }
}
