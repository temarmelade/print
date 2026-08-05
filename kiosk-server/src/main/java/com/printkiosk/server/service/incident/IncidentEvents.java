package com.printkiosk.server.service.incident;

import com.printkiosk.shared.api.IncidentSeverity;
import com.printkiosk.shared.api.IncidentType;

import java.time.Instant;

/**
 * События жизненного цикла инцидента.
 *
 * <p>Нужны, чтобы отвязать отправку уведомлений от записи в БД. Telegram-
 * сообщение нельзя «откатить»: если слать его прямо внутри транзакции, а та
 * упадёт, техник получит сигнал о поломке, которой в базе нет. Поэтому
 * событие публикуется в транзакции, а слушатель срабатывает после коммита.
 *
 * <p>Внутри — снимок полей, а не сущность: к моменту обработки транзакция
 * закрыта, и ленивая подгрузка уже недоступна.
 */
public final class IncidentEvents {

    private IncidentEvents() {}

    /** Проблема появилась. */
    public record Opened(
            long incidentId,
            String kioskId,
            String kioskName,
            String location,
            IncidentType type,
            IncidentSeverity severity,
            String reason,
            Instant startedAt
    ) {}

    /** Проблема устранена. */
    public record Resolved(
            long incidentId,
            String kioskId,
            String kioskName,
            IncidentType type,
            IncidentSeverity severity,
            long durationMinutes
    ) {}
}
