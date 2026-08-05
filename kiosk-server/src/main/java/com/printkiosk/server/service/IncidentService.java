package com.printkiosk.server.service;

import com.printkiosk.server.domain.*;
import com.printkiosk.shared.api.IncidentSeverity;
import com.printkiosk.shared.api.IncidentType;
import com.printkiosk.shared.api.dto.IncidentDto;
import com.printkiosk.shared.api.dto.IncidentSummaryDto;
import com.printkiosk.shared.api.dto.IncidentSummaryDto.KioskIncidentCountDto;
import com.printkiosk.shared.api.dto.IncidentSummaryDto.TypeCountDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Жизненный цикл инцидентов киоска.
 *
 * <p>Телеметрия хранит только «сейчас»: {@code kiosk_telemetry} перезаписывается
 * каждым heartbeat. Этот сервис превращает поток состояний в интервалы —
 * инцидент открывается, когда проблема появилась, и закрывается, когда она
 * исчезла. Так появляются история, время простоя и SLA.
 *
 * <p>Два источника событий, и это принципиально:
 * <ul>
 *   <li><b>heartbeat</b> — проблемы, о которых киоск сообщает сам (замятие,
 *       бумага, тонер, крышка);</li>
 *   <li><b>планировщик</b> — потеря связи. Её нельзя обнаружить в момент
 *       приёма телеметрии: если киоск офлайн, heartbeat просто не приходит.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncidentService {

    /** Нет heartbeat дольше этого — открываем инцидент OFFLINE. Совпадает с TelemetryService. */
    private static final Duration OFFLINE_AFTER = Duration.ofMinutes(3);

    /** Порог «мало расходников» — тот же, что использует TelemetryService. */
    private static final int LOW_SUPPLY_PCT = 15;

    private static final int MAX_PAGE_SIZE = 200;

    private final KioskIncidentRepository incidents;
    private final KioskRepository kiosks;
    private final KioskTelemetryRepository telemetry;

    // ════════════════════════════════════════════════════════════════
    //  Синхронизация состояния → инциденты
    // ════════════════════════════════════════════════════════════════

    /**
     * Приводит открытые инциденты киоска в соответствие с текущим состоянием:
     * появившиеся проблемы открывает, исчезнувшие — закрывает.
     *
     * <p>Вызывается на каждом heartbeat. Инциденты типа OFFLINE здесь не
     * трогаем в смысле открытия — раз телеметрия пришла, связь есть, поэтому
     * открытый OFFLINE закрывается как исчезнувший.
     */
    @Transactional
    public void syncFromTelemetry(KioskEntity kiosk, KioskTelemetryEntity t) {
        if (kiosk == null || t == null) return;

        // В режиме обслуживания киоск выключен намеренно — инциденты не копим,
        // иначе плановая замена картриджа выглядела бы как авария.
        if (kiosk.isMaintenanceMode()) {
            closeAllOpen(kiosk.getId(), "переведён в режим обслуживания");
            return;
        }

        reconcile(kiosk.getId(), detectProblems(kiosk, t));
    }

    /**
     * Проблемы, видимые из телеметрии. Логика намеренно повторяет
     * {@code TelemetryService.deriveHealth}, но возвращает ВСЕ проблемы, а не
     * первую по приоритету: для истории важно, что одновременно кончилась
     * бумага И открыта крышка — иначе вторая всплывёт лишь после починки первой.
     */
    private Map<IncidentType, String> detectProblems(KioskEntity k, KioskTelemetryEntity t) {
        Map<IncidentType, String> found = new LinkedHashMap<>();

        if (t.isPaperJam())   found.put(IncidentType.PAPER_JAM, "Замятие бумаги");
        if (t.isPaperOut())   found.put(IncidentType.PAPER_OUT, "Закончилась бумага");
        if (t.isTonerEmpty()) found.put(IncidentType.TONER_EMPTY, "Закончился тонер");
        if (t.isDoorOpen())   found.put(IncidentType.DOOR_OPEN, "Открыта крышка");

        if (Boolean.FALSE.equals(t.getPrinterOnline())) {
            found.put(IncidentType.PRINTER_OFFLINE, "Принтер не отвечает");
        }
        if (t.getPrinterError() != null && !t.getPrinterError().isBlank()) {
            found.put(IncidentType.PRINTER_ERROR, trim(t.getPrinterError(), 300));
        }

        // Предупреждения о расходниках дублировать не нужно: если тонер уже
        // кончился, «заканчивается тонер» — шум.
        if (!found.containsKey(IncidentType.TONER_EMPTY)) {
            if (t.isTonerLow()) {
                found.put(IncidentType.TONER_LOW, "Заканчивается тонер");
            } else if (t.getTonerPercent() != null && t.getTonerPercent() <= LOW_SUPPLY_PCT) {
                found.put(IncidentType.TONER_LOW, "Мало тонера (~" + t.getTonerPercent() + "%)");
            }
        }
        if (!found.containsKey(IncidentType.PAPER_OUT)
                && t.getPaperPercent() != null && t.getPaperPercent() <= LOW_SUPPLY_PCT) {
            int sheets = t.getPaperPercent() * Math.max(k.getPaperCapacity(), 1) / 100;
            found.put(IncidentType.PAPER_LOW, "Мало бумаги (~" + sheets + " листов)");
        }

        return found;
    }

    /**
     * Сводит желаемое состояние с фактическим: открывает новое, продлевает
     * существующее, закрывает исчезнувшее.
     */
    private void reconcile(String kioskId, Map<IncidentType, String> active) {
        Instant now = Instant.now();

        List<KioskIncidentEntity> open = incidents.findByKioskIdAndResolvedAtIsNull(kioskId);
        Map<IncidentType, KioskIncidentEntity> openByType = open.stream()
                .collect(Collectors.toMap(KioskIncidentEntity::getIncidentType,
                        Function.identity(), (a, b) -> a));

        // Появилось или продолжается.
        for (var e : active.entrySet()) {
            KioskIncidentEntity existing = openByType.get(e.getKey());
            if (existing != null) {
                existing.setOccurrences(existing.getOccurrences() + 1);
                existing.setLastSeenAt(now);
            } else {
                openIncident(kioskId, e.getKey(), e.getValue(), now);
            }
        }

        // Исчезло — закрываем.
        for (KioskIncidentEntity incident : open) {
            if (!active.containsKey(incident.getIncidentType())) {
                resolve(incident, now);
            }
        }
    }

    /**
     * Открывает инцидент. Частичный уникальный индекс из V13 защищает от
     * гонки, если два heartbeat'а пришли одновременно: проигравший получит
     * нарушение constraint'а, и это не ошибка — инцидент уже открыт.
     */
    private void openIncident(String kioskId, IncidentType type, String reason, Instant now) {
        try {
            incidents.saveAndFlush(KioskIncidentEntity.builder()
                    .kioskId(kioskId)
                    .incidentType(type)
                    .severity(type.severity())
                    .reason(trim(reason, 300))
                    .startedAt(now)
                    .lastSeenAt(now)
                    .occurrences(1)
                    .build());
            log.info("Инцидент открыт: киоск={} тип={} причина={}", kioskId, type, reason);
        } catch (DataIntegrityViolationException e) {
            log.debug("Инцидент {} для киоска {} уже открыт (параллельный heartbeat)", type, kioskId);
        }
    }

    private void resolve(KioskIncidentEntity incident, Instant now) {
        incident.setResolvedAt(now);
        long minutes = Duration.between(incident.getStartedAt(), now).toMinutes();
        log.info("Инцидент закрыт: киоск={} тип={} длился={} мин",
                incident.getKioskId(), incident.getIncidentType(), minutes);
    }

    private void closeAllOpen(String kioskId, String why) {
        Instant now = Instant.now();
        List<KioskIncidentEntity> open = incidents.findByKioskIdAndResolvedAtIsNull(kioskId);
        if (open.isEmpty()) return;
        open.forEach(i -> resolve(i, now));
        log.info("Киоск {}: закрыто инцидентов {} ({})", kioskId, open.size(), why);
    }

    // ════════════════════════════════════════════════════════════════
    //  Потеря связи — только по расписанию
    // ════════════════════════════════════════════════════════════════

    /**
     * Ищет киоски, переставшие выходить на связь. Отдельная задача нужна
     * потому, что молчание нельзя заметить в обработчике heartbeat: события
     * просто нет. Интервал меньше порога офлайна, чтобы инцидент открывался
     * без заметной задержки.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void detectOfflineKiosks() {
        Instant now = Instant.now();
        Instant deadline = now.minus(OFFLINE_AFTER);

        for (KioskEntity k : kiosks.findAllByOrderByNameAsc()) {
            if (k.isMaintenanceMode()) continue;

            KioskTelemetryEntity t = telemetry.findById(k.getId()).orElse(null);
            boolean silent = (t == null) || t.getReportedAt().isBefore(deadline);

            Optional<KioskIncidentEntity> open = incidents
                    .findByKioskIdAndIncidentTypeAndResolvedAtIsNull(k.getId(), IncidentType.OFFLINE);

            if (silent && open.isEmpty()) {
                String reason = (t == null)
                        ? "Киоск ни разу не выходил на связь"
                        : "Нет связи с " + t.getReportedAt();
                // Инцидент датируем последним контактом, а не «сейчас»: иначе
                // простой занижался бы на величину порога обнаружения.
                Instant startedAt = (t != null) ? t.getReportedAt() : now;
                openIncident(k.getId(), IncidentType.OFFLINE, reason, startedAt);
            } else if (!silent) {
                open.ifPresent(i -> resolve(i, now));
            } else {
                open.ifPresent(i -> i.setLastSeenAt(now));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Чтение для админки
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<IncidentDto> openIncidents() {
        Map<String, KioskEntity> byId = kioskIndex();
        Instant now = Instant.now();
        return incidents.findOpenOrdered().stream()
                .map(i -> toDto(i, byId.get(i.getKioskId()), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<IncidentDto> history(int days, String kioskId, int page, int size) {
        Instant from = Instant.now().minus(Duration.ofDays(Math.max(days, 1)));
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize);

        String filter = blankToNull(kioskId);
        Page<KioskIncidentEntity> found = (filter == null)
                ? incidents.findByStartedAtGreaterThanEqualOrderByStartedAtDesc(from, pageable)
                : incidents.findByStartedAtGreaterThanEqualAndKioskIdOrderByStartedAtDesc(
                        from, filter, pageable);

        Map<String, KioskEntity> byId = kioskIndex();
        Instant now = Instant.now();
        return found.getContent().stream()
                .map(i -> toDto(i, byId.get(i.getKioskId()), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public IncidentSummaryDto summary(int days) {
        int period = Math.max(days, 1);
        Instant from = Instant.now().minus(Duration.ofDays(period));
        Instant now = Instant.now();

        Map<String, KioskEntity> byId = kioskIndex();

        List<KioskIncidentEntity> open = incidents.findOpenOrdered();
        long openBlocking = open.stream().filter(i -> i.getSeverity() == IncidentSeverity.DOWN).count();
        long openWarning = open.size() - openBlocking;

        // Для сводки берём все инциденты периода: постранично здесь не нужно.
        List<KioskIncidentEntity> all = incidents
                .findByStartedAtGreaterThanEqualOrderByStartedAtDesc(
                        from, PageRequest.of(0, MAX_PAGE_SIZE))
                .getContent();

        List<KioskIncidentEntity> resolved = all.stream()
                .filter(i -> i.getResolvedAt() != null)
                .toList();

        long avgResolution = resolved.isEmpty() ? 0 : Math.round(resolved.stream()
                .mapToLong(i -> minutesBetween(i.getStartedAt(), i.getResolvedAt()))
                .average().orElse(0));

        long downtime = all.stream()
                .filter(i -> i.getSeverity() == IncidentSeverity.DOWN)
                .mapToLong(i -> minutesBetween(i.getStartedAt(),
                        i.getResolvedAt() != null ? i.getResolvedAt() : now))
                .sum();

        return new IncidentSummaryDto(
                period, openBlocking, openWarning, all.size(),
                avgResolution, downtime,
                topTypes(all, now), topKiosks(all, byId, now));
    }

    /** Подтверждение оператором: «увидел, техник выехал». */
    @Transactional
    public void acknowledge(long incidentId, String username) {
        KioskIncidentEntity incident = incidents.findById(incidentId).orElseThrow();
        if (incident.getAcknowledgedAt() == null) {
            incident.setAcknowledgedAt(Instant.now());
            incident.setAcknowledgedBy(trim(username, 120));
            log.info("Инцидент {} подтверждён пользователем {}", incidentId, username);
        }
    }

    /** Ручное закрытие: техник починил, но киоск ещё не прислал heartbeat. */
    @Transactional
    public void resolveManually(long incidentId) {
        KioskIncidentEntity incident = incidents.findById(incidentId).orElseThrow();
        if (incident.isOpen()) {
            resolve(incident, Instant.now());
        }
    }

    // ── Внутреннее ──

    private List<TypeCountDto> topTypes(List<KioskIncidentEntity> all, Instant now) {
        Map<IncidentType, List<KioskIncidentEntity>> grouped = all.stream()
                .collect(Collectors.groupingBy(KioskIncidentEntity::getIncidentType));

        return grouped.entrySet().stream()
                .map(e -> new TypeCountDto(
                        e.getKey(),
                        e.getKey().title(),
                        e.getValue().size(),
                        e.getValue().stream()
                                .mapToLong(i -> minutesBetween(i.getStartedAt(),
                                        i.getResolvedAt() != null ? i.getResolvedAt() : now))
                                .sum()))
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();
    }

    private List<KioskIncidentCountDto> topKiosks(List<KioskIncidentEntity> all,
                                                  Map<String, KioskEntity> byId,
                                                  Instant now) {
        Map<String, List<KioskIncidentEntity>> grouped = all.stream()
                .collect(Collectors.groupingBy(KioskIncidentEntity::getKioskId));

        return grouped.entrySet().stream()
                .map(e -> new KioskIncidentCountDto(
                        e.getKey(),
                        nameOf(byId.get(e.getKey()), e.getKey()),
                        e.getValue().size(),
                        e.getValue().stream()
                                .filter(i -> i.getSeverity() == IncidentSeverity.DOWN)
                                .mapToLong(i -> minutesBetween(i.getStartedAt(),
                                        i.getResolvedAt() != null ? i.getResolvedAt() : now))
                                .sum()))
                .sorted((a, b) -> Long.compare(b.downtimeMinutes(), a.downtimeMinutes()))
                .toList();
    }

    private Map<String, KioskEntity> kioskIndex() {
        return kiosks.findAllByOrderByNameAsc().stream()
                .collect(Collectors.toMap(KioskEntity::getId, Function.identity(), (a, b) -> a));
    }

    private IncidentDto toDto(KioskIncidentEntity i, KioskEntity k, Instant now) {
        Instant end = (i.getResolvedAt() != null) ? i.getResolvedAt() : now;
        return new IncidentDto(
                i.getId(),
                i.getKioskId(),
                nameOf(k, i.getKioskId()),
                k != null ? k.getLocation() : null,
                i.getIncidentType(),
                i.getSeverity(),
                i.getIncidentType().title(),
                i.getReason(),
                i.getStartedAt(),
                i.getResolvedAt(),
                minutesBetween(i.getStartedAt(), end),
                i.getOccurrences(),
                i.getAcknowledgedAt(),
                i.getAcknowledgedBy());
    }

    private static String nameOf(KioskEntity k, String fallback) {
        return (k != null && k.getName() != null) ? k.getName() : fallback;
    }

    private static long minutesBetween(Instant from, Instant to) {
        if (from == null || to == null) return 0;
        return Math.max(Duration.between(from, to).toMinutes(), 0);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
