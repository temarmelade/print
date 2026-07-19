package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.SupplySource;

/**
 * Что киоск шлёт на сервер (heartbeat + состояние принтера).
 *
 * <p>Уровни — {@link Integer}, а не int: null означает «принтер не сообщает».
 * Ноль означал бы «кончилось», а это разные вещи. Бытовые модели вроде
 * Canon MF232w часто не отдают точных уровней вовсе.
 */
public record TelemetryReport(
        String clientVersion,

        Boolean printerOnline,
        Integer tonerPercent,
        Integer paperPercent,
        SupplySource tonerSource,
        SupplySource paperSource,

        boolean paperOut,
        boolean paperJam,
        boolean tonerLow,
        boolean tonerEmpty,
        boolean doorOpen,
        String printerError,

        Integer pageCounter
) {}
