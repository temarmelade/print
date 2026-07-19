package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.printer.PrinterProbe;
import com.printkiosk.shared.api.dto.TelemetryReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heartbeat: раз в 30 секунд опрашивает принтер и шлёт состояние на сервер.
 *
 * <p>Сам факт регулярных запросов и есть признак «киоск жив»: сервер считает
 * терминал офлайн, если не получал телеметрию дольше 3 минут.
 *
 * <p>Ошибки отправки НЕ роняют киоск и не мешают печати — телеметрия
 * второстепенна по отношению к обслуживанию клиента у терминала.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryReporter {

    private final PrinterProbe probe;
    private final KioskServerClient server;

    @Value("${kiosk.telemetry.enabled:true}")
    private boolean enabled;

    @Value("${kiosk.client-version:dev}")
    private String clientVersion;

    @Scheduled(
            initialDelayString = "${kiosk.telemetry.initial-delay-ms:10000}",
            fixedRateString = "${kiosk.telemetry.interval-ms:30000}")
    public void report() {
        if (!enabled) return;

        try {
            PrinterProbe.Reading r = probe.probe();

            TelemetryReport payload = new TelemetryReport(
                    clientVersion,
                    r.online(),
                    r.tonerPercent(),
                    r.paperPercent(),
                    r.tonerSource(),
                    r.paperSource(),
                    r.paperOut(),
                    r.paperJam(),
                    r.tonerLow(),
                    r.tonerEmpty(),
                    r.doorOpen(),
                    r.error(),
                    r.pageCounter());

            server.sendTelemetry(payload);
            log.debug("Телеметрия отправлена: toner={} paper={} pages={}",
                    r.tonerPercent(), r.paperPercent(), r.pageCounter());

        } catch (Exception e) {
            // Сеть/сервер могут лежать — киоск обязан продолжать печатать.
            log.warn("Не удалось отправить телеметрию: {}", e.getMessage());
        }
    }
}
