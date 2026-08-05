package com.printkiosk.server.service;

import com.printkiosk.server.domain.*;
import com.printkiosk.shared.api.KioskHealth;
import com.printkiosk.shared.api.SupplySource;
import com.printkiosk.shared.api.dto.KioskDto;
import com.printkiosk.shared.api.dto.TelemetryReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Приём телеметрии от киосков и вычисление их «здоровья» для карты сети.
 *
 * <p>Главный принцип: <b>null ≠ 0</b>. Если принтер не сообщает уровень тонера,
 * мы показываем «неизвестно», а не «пусто». Врать о состоянии расходников
 * хуже, чем честно сказать «не знаю» — иначе техник поедет впустую.
 *
 * <p>Про Canon MF232w: у кассеты, скорее всего, нет датчика уровня бумаги
 * (принтер знает лишь «есть / кончилась»). Поэтому процент бумаги мы считаем
 * программно — по счётчику напечатанных страниц с момента, когда техник
 * отметил заправку. Это честная ОЦЕНКА, и она помечена как ESTIMATE.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TelemetryService {

    /** Нет heartbeat дольше этого — киоск считается офлайн (🔴). */
    private static final Duration OFFLINE_AFTER = Duration.ofMinutes(3);

    /** Ниже этого порога — жёлтая точка: пора планировать выезд. */
    private static final int LOW_SUPPLY_PCT = 15;

    private final KioskRepository kiosks;
    private final KioskTelemetryRepository telemetry;
    private final KioskTelemetryHistoryRepository history;
    private final IncidentService incidents;

    // ════════════════════════════════════════════════════════════════
    //  Приём телеметрии от киоска
    // ════════════════════════════════════════════════════════════════

    @Transactional
    public void ingest(String kioskId, TelemetryReport r) {
        KioskEntity kiosk = kiosks.findById(kioskId).orElse(null);
        if (kiosk == null) {
            log.warn("Telemetry from unknown kiosk: {}", kioskId);
            return;
        }

        Instant now = Instant.now();

        // Бумага: если принтер не отдал уровень — оцениваем по счётчику страниц.
        Integer paperPct = r.paperPercent();
        SupplySource paperSrc = r.paperSource() != null ? r.paperSource() : SupplySource.UNKNOWN;
        if (paperPct == null) {
            Integer est = estimatePaperPercent(kiosk, r.pageCounter());
            if (est != null) {
                paperPct = est;
                paperSrc = SupplySource.ESTIMATE;
            }
        }
        // Принтер прямо говорит «бумага кончилась» — это факт, важнее любой оценки.
        if (r.paperOut()) {
            paperPct = 0;
        }

        // Тонер: аналогично — оценка по ресурсу картриджа, если датчика нет.
        Integer tonerPct = r.tonerPercent();
        SupplySource tonerSrc = r.tonerSource() != null ? r.tonerSource() : SupplySource.UNKNOWN;
        if (tonerPct == null) {
            Integer est = estimateTonerPercent(kiosk, r.pageCounter());
            if (est != null) {
                tonerPct = est;
                tonerSrc = SupplySource.ESTIMATE;
            }
        }
        if (r.tonerEmpty()) {
            tonerPct = 0;
        }

        KioskTelemetryEntity t = telemetry.findById(kioskId)
                .orElseGet(() -> KioskTelemetryEntity.builder().kioskId(kioskId).build());

        t.setReportedAt(now);
        t.setClientVersion(r.clientVersion());
        t.setPrinterOnline(r.printerOnline());
        t.setTonerPercent(clampPct(tonerPct));
        t.setPaperPercent(clampPct(paperPct));
        t.setTonerSource(tonerSrc.name());
        t.setPaperSource(paperSrc.name());
        t.setPaperOut(r.paperOut());
        t.setPaperJam(r.paperJam());
        t.setTonerLow(r.tonerLow());
        t.setTonerEmpty(r.tonerEmpty());
        t.setDoorOpen(r.doorOpen());
        t.setPrinterError(trim(r.printerError(), 200));
        t.setPageCounter(r.pageCounter());

        telemetry.save(t);

        // История — топливо для предиктивной аналитики («бумага кончится завтра к 14:30»).
        history.save(KioskTelemetryHistoryEntity.builder()
                .kioskId(kioskId)
                .recordedAt(now)
                .tonerPercent(t.getTonerPercent())
                .paperPercent(t.getPaperPercent())
                .pageCounter(t.getPageCounter())
                .build());

        // Состояние → интервалы: открываем появившиеся проблемы, закрываем ушедшие.
        // Телеметрия хранит только «сейчас», а для SLA нужна история.
        incidents.syncFromTelemetry(kiosk, t);
    }

    // ════════════════════════════════════════════════════════════════
    //  Оценка расходников (когда датчиков нет)
    // ════════════════════════════════════════════════════════════════

    /**
     * Бумага = ёмкость кассеты минус страницы, напечатанные с момента заправки.
     * Требует счётчика страниц принтера и отметки «заправил» от техника.
     */
    private Integer estimatePaperPercent(KioskEntity k, Integer pageCounter) {
        if (pageCounter == null || k.getPagesAtPaperRefill() == null) return null;

        int printed = pageCounter - k.getPagesAtPaperRefill();
        if (printed < 0) return null;   // счётчик сбросили — оценка недостоверна

        int capacity = Math.max(k.getPaperCapacity(), 1);
        int left = Math.max(capacity - printed, 0);
        return left * 100 / capacity;
    }

    /** Тонер = ресурс картриджа минус напечатанное с момента замены. */
    private Integer estimateTonerPercent(KioskEntity k, Integer pageCounter) {
        if (pageCounter == null || k.getPagesAtCartridgeChange() == null) return null;

        int printed = pageCounter - k.getPagesAtCartridgeChange();
        if (printed < 0) return null;

        int yield = Math.max(k.getCartridgeYield(), 1);
        int left = Math.max(yield - printed, 0);
        return left * 100 / yield;
    }

    // ════════════════════════════════════════════════════════════════
    //  Карточки для админки + цвет точки
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<KioskDto> list() {
        return kiosks.findAllByOrderByNameAsc().stream()
                .map(k -> toDto(k, telemetry.findById(k.getId()).orElse(null)))
                .toList();
    }

    private KioskDto toDto(KioskEntity k, KioskTelemetryEntity t) {
        boolean online = t != null
                && Duration.between(t.getReportedAt(), Instant.now()).compareTo(OFFLINE_AFTER) < 0;

        Health h = deriveHealth(k, t, online);

        Integer sheetsLeft = null;
        if (t != null && t.getPaperPercent() != null) {
            sheetsLeft = t.getPaperPercent() * k.getPaperCapacity() / 100;
        }

        return new KioskDto(
                k.getId(), k.getName(), k.getLocation(), k.getLatitude(), k.getLongitude(),
                h.health(), h.reason(), online, k.isMaintenanceMode(),
                t != null ? t.getReportedAt() : null,
                t != null ? t.getTonerPercent() : null,
                source(t != null ? t.getTonerSource() : null),
                t != null ? t.getPaperPercent() : null,
                source(t != null ? t.getPaperSource() : null),
                sheetsLeft,
                t != null && t.isPaperOut(),
                t != null && t.isPaperJam(),
                t != null && t.isTonerLow(),
                t != null && t.isTonerEmpty(),
                t != null && t.isDoorOpen(),
                t != null ? t.getPrinterError() : null,
                t != null ? t.getPageCounter() : null,
                k.getPaperRefilledAt(), k.getCartridgeChangedAt());
    }

    private record Health(KioskHealth health, String reason) {}

    /**
     * Цвет точки на карте. Порядок важен: сначала то, что ломает печать
     * (🔴), потом то, что её лишь угрожает прервать (🟡).
     */
    private Health deriveHealth(KioskEntity k, KioskTelemetryEntity t, boolean online) {
        if (k.isMaintenanceMode()) {
            return new Health(KioskHealth.MAINTENANCE, "Режим обслуживания");
        }
        if (t == null) {
            return new Health(KioskHealth.DOWN, "Нет связи: киоск ни разу не выходил на связь");
        }
        if (!online) {
            long min = Duration.between(t.getReportedAt(), Instant.now()).toMinutes();
            return new Health(KioskHealth.DOWN, "Нет связи более " + min + " мин");
        }
        if (t.isPaperJam())   return new Health(KioskHealth.DOWN, "Замятие бумаги");
        if (t.isPaperOut())   return new Health(KioskHealth.DOWN, "Закончилась бумага");
        if (t.isTonerEmpty()) return new Health(KioskHealth.DOWN, "Закончился тонер");
        if (t.isDoorOpen())   return new Health(KioskHealth.DOWN, "Открыта крышка");
        if (Boolean.FALSE.equals(t.getPrinterOnline())) {
            return new Health(KioskHealth.DOWN, "Принтер не отвечает");
        }
        if (t.getPrinterError() != null && !t.getPrinterError().isBlank()) {
            return new Health(KioskHealth.DOWN, t.getPrinterError());
        }

        if (t.isTonerLow()) {
            return new Health(KioskHealth.WARNING, "Заканчивается тонер");
        }
        if (t.getPaperPercent() != null && t.getPaperPercent() <= LOW_SUPPLY_PCT) {
            int sheets = t.getPaperPercent() * k.getPaperCapacity() / 100;
            return new Health(KioskHealth.WARNING, "Мало бумаги (~" + sheets + " листов)");
        }
        if (t.getTonerPercent() != null && t.getTonerPercent() <= LOW_SUPPLY_PCT) {
            return new Health(KioskHealth.WARNING, "Мало тонера (~" + t.getTonerPercent() + "%)");
        }

        return new Health(KioskHealth.OK, "Работает");
    }

    // ── Обслуживание (кнопки техника) ──

    @Transactional
    public void markPaperRefilled(String kioskId) {
        KioskEntity k = kiosks.findById(kioskId).orElseThrow();
        Integer counter = telemetry.findById(kioskId)
                .map(KioskTelemetryEntity::getPageCounter).orElse(null);
        k.setPagesAtPaperRefill(counter);
        k.setPaperRefilledAt(Instant.now());
        log.info("Kiosk {}: бумага заправлена (счётчик={})", kioskId, counter);
    }

    @Transactional
    public void markCartridgeChanged(String kioskId) {
        KioskEntity k = kiosks.findById(kioskId).orElseThrow();
        Integer counter = telemetry.findById(kioskId)
                .map(KioskTelemetryEntity::getPageCounter).orElse(null);
        k.setPagesAtCartridgeChange(counter);
        k.setCartridgeChangedAt(Instant.now());
        log.info("Kiosk {}: картридж заменён (счётчик={})", kioskId, counter);
    }

    @Transactional
    public void setMaintenance(String kioskId, boolean on) {
        kiosks.findById(kioskId).orElseThrow().setMaintenanceMode(on);
    }

    // ── Внутреннее ──

    private static SupplySource source(String s) {
        if (s == null) return SupplySource.UNKNOWN;
        try { return SupplySource.valueOf(s); }
        catch (IllegalArgumentException e) { return SupplySource.UNKNOWN; }
    }

    private static Integer clampPct(Integer v) {
        if (v == null) return null;
        return Math.min(Math.max(v, 0), 100);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
