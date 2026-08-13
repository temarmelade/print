package com.printkiosk.server.web;

import com.printkiosk.server.service.KioskAdminService;
import com.printkiosk.server.security.AdminPrincipal;
import com.printkiosk.server.service.KioskCommandService;
import com.printkiosk.server.service.SupplyForecastService;
import com.printkiosk.server.service.TelemetryService;
import com.printkiosk.shared.api.KioskCommandType;
import com.printkiosk.shared.api.dto.KioskCommandDto;
import com.printkiosk.shared.api.dto.KioskDto;
import com.printkiosk.shared.api.dto.SupplyForecastDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Модуль «Терминалы». Смотреть могут все вошедшие (техник — его основной
 * инструмент), а заводить/удалять киоски и выпускать ключи — только владелец.
 */
@RestController
@RequestMapping("/api/admin/kiosks")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AdminKioskController {

    private final TelemetryService telemetry;
    private final KioskAdminService admin;
    private final SupplyForecastService forecasts;
    private final KioskCommandService commands;

    @GetMapping
    public List<KioskDto> list() {
        return telemetry.list();
    }

    /** Регистрация киоска. Ключ возвращается ОДИН раз — потом только хеш. */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public CreatedKiosk create(@Valid @RequestBody CreateKioskRequest req) {
        return admin.create(req);
    }

    /** Перевыпуск ключа (компрометация / переустановка киоска). */
    @PostMapping("/{id}/rotate-key")
    @PreAuthorize("hasRole('OWNER')")
    public CreatedKiosk rotateKey(@PathVariable String id) {
        return admin.rotateKey(id);
    }

    /** Редактирование: название, адрес, координаты для карты, ёмкости. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> update(@PathVariable String id,
                                       @Valid @RequestBody UpdateKioskRequest req) {
        admin.update(id, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Прогноз расхода: когда закончатся бумага и тонер. Строится на истории
     * телеметрии, поэтому на свежем киоске вернёт «данных пока нет».
     */
    @GetMapping("/{id}/forecast")
    public SupplyForecastDto forecast(@PathVariable String id) {
        return forecasts.forecast(id);
    }

    // ── Кнопки техника ──

    @PostMapping("/{id}/paper-refilled")
    public ResponseEntity<Void> paperRefilled(@PathVariable String id) {
        telemetry.markPaperRefilled(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cartridge-changed")
    public ResponseEntity<Void> cartridgeChanged(@PathVariable String id) {
        telemetry.markCartridgeChanged(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/maintenance")
    public ResponseEntity<Void> maintenance(@PathVariable String id,
                                            @RequestParam boolean enabled) {
        telemetry.setMaintenance(id, enabled);
        return ResponseEntity.noContent().build();
    }

    // ── Дистанционные команды ──

    /**
     * Ставит команду в очередь. Киоск заберёт её на ближайшем heartbeat,
     * то есть в пределах 30 секунд.
     *
     * <p>Доступ у владельца и техника: перезагрузка терминала — обычная
     * работа выездного инженера, а вот поддержке она не нужна.
     */
    @PostMapping("/{id}/commands")
    @PreAuthorize("hasAnyRole('OWNER','TECHNICIAN')")
    public KioskCommandDto sendCommand(@PathVariable String id,
                                       @RequestParam KioskCommandType type,
                                       @AuthenticationPrincipal AdminPrincipal operator) {
        // Именно username, а не Principal.getName(): в SecurityContext лежит
        // объект AdminPrincipal, и getName() вернул бы его toString() целиком
        // («AdminPrincipal[id=..., username=..., role=...]») — это заведомо
        // длиннее колонки created_by и роняет вставку.
        return commands.enqueue(id, type, operator != null ? operator.username() : "unknown");
    }

    /** История команд точки — кто и когда перезагружал. */
    @GetMapping("/{id}/commands")
    public List<KioskCommandDto> commandHistory(@PathVariable String id) {
        return commands.history(id);
    }

    /** Отзыв команды, которую киоск ещё не забрал. */
    @DeleteMapping("/commands/{commandId}")
    @PreAuthorize("hasAnyRole('OWNER','TECHNICIAN')")
    public ResponseEntity<Void> cancelCommand(@PathVariable UUID commandId) {
        commands.cancel(commandId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        admin.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Тела ──

    public record CreateKioskRequest(
            @NotBlank String id,
            @NotBlank String name,
            String location,
            Double latitude,
            Double longitude,
            Integer paperCapacity,
            Integer cartridgeYield) {}

    /** Все поля необязательные: присылаем только то, что меняем. */
    public record UpdateKioskRequest(
            String name,
            String location,
            Double latitude,
            Double longitude,
            Integer paperCapacity,
            Integer cartridgeYield) {}

    /** Ключ показывается один раз — сохраните его в конфиг киоска. */
    public record CreatedKiosk(String id, String name, String apiKey) {}
}