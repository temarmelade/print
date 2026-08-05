package com.printkiosk.server.web;

import com.printkiosk.server.service.IncidentService;
import com.printkiosk.shared.api.dto.IncidentDto;
import com.printkiosk.shared.api.dto.IncidentSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Инциденты киосков. Доступны любому вошедшему: техник должен видеть
 * поломки, даже если финансовые разделы ему закрыты.
 */
@RestController
@RequestMapping("/api/admin/incidents")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AdminIncidentController {

    private final IncidentService incidents;

    /** Открытые инциденты — главный экран. */
    @GetMapping
    public List<IncidentDto> open() {
        return incidents.openIncidents();
    }

    /** История за период. */
    @GetMapping("/history")
    public List<IncidentDto> history(@RequestParam(defaultValue = "30") int days,
                                     @RequestParam(required = false) String kioskId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return incidents.history(days, kioskId, page, size);
    }

    /** Сводка: открытые, среднее время устранения, простой, частые причины. */
    @GetMapping("/summary")
    public IncidentSummaryDto summary(@RequestParam(defaultValue = "30") int days) {
        return incidents.summary(days);
    }

    /** «Увидел, техник выехал». */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable long id, Authentication auth) {
        incidents.acknowledge(id, auth != null ? auth.getName() : null);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ручное закрытие: техник починил на месте, а heartbeat ещё не пришёл.
     * Если проблема на самом деле осталась, следующий heartbeat откроет
     * инцидент заново — это нормально и не требует отдельной обработки.
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable long id) {
        incidents.resolveManually(id);
        return ResponseEntity.noContent().build();
    }
}
