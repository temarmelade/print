package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.KioskCommandType;

import java.util.UUID;

/**
 * Ответ на heartbeat. Раньше эндпоинт телеметрии возвращал пустое тело —
 * теперь это обратный канал до киоска: сервер подкладывает команду,
 * которую киоску нужно выполнить.
 *
 * <p>Команда не более одной: уникальный частичный индекс
 * {@code uniq_kiosk_pending_command} не даёт накопить очередь.
 * Оба поля null — делать нечего, обычный случай.
 */
public record TelemetryResponse(
        UUID commandId,
        KioskCommandType command
) {
    public static TelemetryResponse nothing() {
        return new TelemetryResponse(null, null);
    }

    public boolean hasCommand() {
        return commandId != null && command != null;
    }
}
