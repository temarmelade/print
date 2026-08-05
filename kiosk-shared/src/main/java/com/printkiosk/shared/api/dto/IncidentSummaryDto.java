package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.IncidentType;

import java.util.List;

/** Сводка по инцидентам за период — шапка экрана «Инциденты». */
public record IncidentSummaryDto(
        int periodDays,

        /** Сейчас открыто и блокирует печать. */
        long openBlocking,

        /** Сейчас открыто, но киоск ещё печатает. */
        long openWarning,

        /** Всего инцидентов за период (включая закрытые). */
        long totalInPeriod,

        /** Среднее время до устранения, минуты (только закрытые). */
        long avgResolutionMinutes,

        /** Суммарный простой из-за блокирующих инцидентов, минуты. */
        long totalDowntimeMinutes,

        /** Частые причины за период — что чинить в первую очередь. */
        List<TypeCountDto> topTypes,

        /** Проблемные точки за период. */
        List<KioskIncidentCountDto> topKiosks
) {

    public record TypeCountDto(
            IncidentType incidentType,
            String title,
            long count,
            long totalMinutes
    ) {}

    public record KioskIncidentCountDto(
            String kioskId,
            String kioskName,
            long count,
            long downtimeMinutes
    ) {}
}
