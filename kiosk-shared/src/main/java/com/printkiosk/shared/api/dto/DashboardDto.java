package com.printkiosk.shared.api.dto;

import java.util.List;

/**
 * Сводка для дашборда.
 *
 * <p>Денежные поля — {@link Long}, а не long: техник финансы не видит, и сервер
 * отдаёт ему {@code null}, а не ноль. Ноль означал бы «выручки не было», что
 * неправда — это «вам не положено».
 */
public record DashboardDto(
        int periodDays,

        // Сегодня
        Long todayRevenueSom,
        long todayPaidJobs,
        long todayPages,

        // Период (по умолчанию 30 дней)
        Long periodRevenueSom,
        long periodPaidJobs,
        long periodPages,
        long periodFailedJobs,

        List<DailyPointDto> daily,
        List<KioskStatDto> byKiosk
) {
    /** Точка дневного графика. */
    public record DailyPointDto(String date, Long revenueSom, long paidJobs) {}

    /** Строка разбивки по киоскам. */
    public record KioskStatDto(String kioskId, Long revenueSom, long paidJobs, long pages) {}
}
