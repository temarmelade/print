package com.printkiosk.server.web;

import com.printkiosk.server.service.KioskAdminService;
import com.printkiosk.server.service.TelemetryService;
import com.printkiosk.shared.api.dto.KioskDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** Ключ показывается один раз — сохраните его в конфиг киоска. */
    public record CreatedKiosk(String id, String name, String apiKey) {}
}
