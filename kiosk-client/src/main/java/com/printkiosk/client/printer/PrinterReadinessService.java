package com.printkiosk.client.printer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.time.Duration;
import java.time.Instant;

/**
 * Синхронная проверка: готов ли принтер принять задание.
 * <p>
 * Логика:
 * <ul>
 *   <li>Если SNMP не включён — проверяем только наличие физического принтера
 *       в системе (PrintServiceLookup).</li>
 *   <li>Если SNMP включён — берём последний snapshot. Если он старше 2 минут —
 *       считаем принтер недоступным (поллер потерял связь).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterReadinessService {

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);

    private final PrinterMonitor monitor;

    public boolean isReady() {
        if (PrintServiceLookup.lookupDefaultPrintService() == null
                && PrintServiceLookup.lookupPrintServices(null, null).length == 0) {
            log.warn("Readiness: no system printers found");
            return false;
        }

        if (!monitor.snmpEnabled()) {
            return true;  // USB-принтер; верим системе
        }

        PrinterMonitor.Snapshot snap = monitor.lastSnapshot();
        if (snap == null) {
            log.warn("Readiness: SNMP enabled but no snapshot yet");
            return false;
        }
        Duration age = Duration.between(snap.at(), Instant.now());
        if (age.compareTo(STALE_THRESHOLD) > 0) {
            log.warn("Readiness: SNMP snapshot stale ({}s old)", age.toSeconds());
            return false;
        }
        if (!snap.isReady()) {
            log.warn("Readiness: SNMP reports low supplies (toner={}%, paper={}%)",
                    snap.tonerPct(), snap.paperPct());
            return false;
        }
        return true;
    }
}
