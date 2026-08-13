package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.printer.PrinterProbe;
import com.printkiosk.shared.api.dto.CommandAckRequest;
import com.printkiosk.shared.api.dto.TelemetryReport;
import com.printkiosk.shared.api.dto.TelemetryResponse;
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
    private final RemoteCommandExecutor commandExecutor;

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

            TelemetryResponse response = server.sendTelemetry(payload);
            log.debug("Телеметрия отправлена: toner={} paper={} pages={}",
                    r.tonerPercent(), r.paperPercent(), r.pageCounter());

            handleCommand(response);

        } catch (Exception e) {
            // Сеть/сервер могут лежать — киоск обязан продолжать печатать.
            log.warn("Не удалось отправить телеметрию: {}", e.getMessage());
        }
    }

    /**
     * Разбирает команду из ответа сервера.
     *
     * <p>Отказ подтверждается сразу — оператор в админке увидит причину
     * («киоск обслуживает клиента») вместо молчания и не будет гадать,
     * дошла команда или нет.
     */
    private void handleCommand(TelemetryResponse response) {
        if (response == null || !response.hasCommand()) return;

        String refusal = commandExecutor.tryExecute(
                response,
                () -> server.ackCommand(new CommandAckRequest(
                        response.commandId(), true, "Команда принята, выполняем")));

        if (refusal != null) {
            log.warn("Команда {} отклонена: {}", response.command(), refusal);
            server.ackCommand(new CommandAckRequest(response.commandId(), false, refusal));
        }
    }
}
