package com.printkiosk.server.service;

import com.printkiosk.server.domain.*;
import com.printkiosk.shared.api.dto.SupplyForecastDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Прогноз расхода бумаги и тонера — «при текущем темпе бумага кончится
 * завтра к 14:30» из ТЗ.
 *
 * <p>Таблица {@code kiosk_telemetry_history} наполнялась с Фазы 2, но до сих
 * пор не читалась. Здесь она наконец используется: по счётчику страниц
 * считается темп печати, а остаток расходников делится на этот темп.
 *
 * <p>Осторожность важнее полноты. Прогноз возвращает {@code null}, если:
 * <ul>
 *   <li>точек истории мало или окно наблюдения слишком короткое;</li>
 *   <li>счётчик страниц сбрасывался (замена принтера) — темп недостоверен;</li>
 *   <li>киоск ничего не печатал: делить на ноль нельзя, а «никогда не
 *       кончится» — честнее, чем выдумывать дату.</li>
 * </ul>
 * Ошибочный прогноз хуже его отсутствия: техник поедет не на ту точку.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupplyForecastService {

    /** Окно наблюдения: неделя сглаживает выходные и всплески перед сессией. */
    private static final Duration WINDOW = Duration.ofDays(7);

    /** Меньше этого числа точек — статистики нет. */
    private static final int MIN_SAMPLES = 3;

    /** Короче этого окна темп считать нельзя: случайный всплеск исказит всё. */
    private static final Duration MIN_SPAN = Duration.ofHours(6);

    /** Дальше этого горизонта прогноз бессмысленен. */
    private static final int MAX_DAYS = 365;

    private final KioskRepository kiosks;
    private final KioskTelemetryRepository telemetry;
    private final KioskTelemetryHistoryRepository history;

    @Transactional(readOnly = true)
    public SupplyForecastDto forecast(String kioskId) {
        KioskEntity k = kiosks.findById(kioskId).orElse(null);
        if (k == null) return empty(kioskId);

        List<KioskTelemetryHistoryEntity> points = history
                .findByKioskIdAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(
                        kioskId, Instant.now().minus(WINDOW));

        Double perDay = pagesPerDay(points);
        if (perDay == null) {
            return new SupplyForecastDto(kioskId, null, null, null, null, null,
                    points.size(), spanHours(points));
        }

        KioskTelemetryEntity t = telemetry.findById(kioskId).orElse(null);

        Integer paperLeft = remaining(t == null ? null : t.getPaperPercent(), k.getPaperCapacity());
        Integer tonerLeft = remaining(t == null ? null : t.getTonerPercent(), k.getCartridgeYield());

        Integer paperDays = daysLeft(paperLeft, perDay);
        Integer tonerDays = daysLeft(tonerLeft, perDay);

        return new SupplyForecastDto(
                kioskId, round1(perDay),
                etaFrom(paperDays), paperDays,
                etaFrom(tonerDays), tonerDays,
                points.size(), spanHours(points));
    }

    /**
     * Темп печати по счётчику страниц.
     *
     * <p>Суммируем только положительные приросты: счётчик может обнулиться
     * при замене принтера, и разность «последний минус первый» тогда уйдёт в
     * минус, испортив прогноз. Пропуски (null) просто игнорируем.
     */
    private static Double pagesPerDay(List<KioskTelemetryHistoryEntity> points) {
        if (points.size() < MIN_SAMPLES) return null;

        Duration span = Duration.between(
                points.get(0).getRecordedAt(),
                points.get(points.size() - 1).getRecordedAt());
        if (span.compareTo(MIN_SPAN) < 0) return null;

        long printed = 0;
        Integer prev = null;
        for (KioskTelemetryHistoryEntity p : points) {
            Integer c = p.getPageCounter();
            if (c == null) continue;
            if (prev != null && c > prev) {
                printed += (c - prev);
            }
            prev = c;
        }

        if (printed <= 0) return null;   // ничего не печатали — прогноза нет

        double days = span.toMinutes() / (60.0 * 24.0);
        return printed / days;
    }

    /** Остаток расходника в страницах. */
    private static Integer remaining(Integer percent, int capacity) {
        if (percent == null) return null;
        return Math.max(percent, 0) * Math.max(capacity, 1) / 100;
    }

    private static Integer daysLeft(Integer remainingPages, double perDay) {
        if (remainingPages == null || perDay <= 0) return null;
        long days = Math.round(remainingPages / perDay);
        return (int) Math.min(Math.max(days, 0), MAX_DAYS);
    }

    private static Instant etaFrom(Integer days) {
        return days == null ? null : Instant.now().plus(Duration.ofDays(days));
    }

    private static long spanHours(List<KioskTelemetryHistoryEntity> points) {
        if (points.size() < 2) return 0;
        return Duration.between(points.get(0).getRecordedAt(),
                points.get(points.size() - 1).getRecordedAt()).toHours();
    }

    private static SupplyForecastDto empty(String kioskId) {
        return new SupplyForecastDto(kioskId, null, null, null, null, null, 0, 0);
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
