package com.printkiosk.server.web;

import com.printkiosk.server.service.KioskCommandService;
import com.printkiosk.server.service.TelemetryService;
import com.printkiosk.shared.api.dto.CommandAckRequest;
import com.printkiosk.shared.api.dto.TelemetryReport;
import com.printkiosk.shared.api.dto.TelemetryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Heartbeat + телеметрия от киоска. Требует X-Kiosk-Id и X-Kiosk-Key.
 *
 * <p>Важно: kioskId берём из аутентификации, а НЕ из тела запроса — иначе
 * один киоск мог бы слать телеметрию от имени другого.
 */
@RestController
@RequestMapping("/api/kiosk")
@PreAuthorize("hasRole('KIOSK')")
@RequiredArgsConstructor
public class KioskTelemetryController {

    private final TelemetryService telemetry;
    private final KioskCommandService commands;

    /**
     * Heartbeat. Ответ — обратный канал до киоска: раньше здесь было пустое
     * тело, теперь сервер может подложить команду на выполнение.
     * Отдельного канала связи не понадобилось: киоск и так стучится сюда
     * раз в 30 секунд, а входящее соединение до него не пробить.
     */
    @PostMapping("/telemetry")
    public TelemetryResponse report(@AuthenticationPrincipal String kioskId,
                                    @RequestBody TelemetryReport report) {
        telemetry.ingest(kioskId, report);
        return commands.pullFor(kioskId);
    }

    /**
     * Подтверждение команды. Киоск отвечает ДО того, как перезагрузиться —
     * после перезагрузки подтверждать уже некому.
     */
    @PostMapping("/commands/ack")
    public ResponseEntity<Void> ack(@AuthenticationPrincipal String kioskId,
                                    @RequestBody CommandAckRequest req) {
        commands.ack(kioskId, req.commandId(), req.accepted(), req.message());
        return ResponseEntity.noContent().build();
    }
}
