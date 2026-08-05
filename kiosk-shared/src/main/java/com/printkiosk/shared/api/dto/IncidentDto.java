package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.IncidentSeverity;
import com.printkiosk.shared.api.IncidentType;

import java.time.Instant;
import java.util.List;

/** Инцидент киоска для ленты в админке. */
public record IncidentDto(
        long id,
        String kioskId,
        String kioskName,           // из реестра киосков, чтобы UI не джойнил
        String location,
        IncidentType incidentType,
        IncidentSeverity severity,
        String title,               // человекочитаемое название типа
        String reason,              // снимок причины на момент открытия
        Instant startedAt,
        Instant resolvedAt,         // null = ещё открыт
        long durationMinutes,       // для открытых — сколько длится прямо сейчас
        int occurrences,
        Instant acknowledgedAt,
        String acknowledgedBy
) {
    public boolean isOpen() {
        return resolvedAt == null;
    }
}
