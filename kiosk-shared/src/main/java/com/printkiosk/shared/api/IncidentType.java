package com.printkiosk.shared.api;

/**
 * Стабильный код проблемы киоска.
 *
 * <p>Нужен потому, что текст причины из телеметрии содержит динамику
 * («Нет связи более 5 мин», «Мало бумаги (~30 листов)»): по нему нельзя
 * понять, тот же это инцидент или новый. Тип — признак тождества, текст —
 * лишь снимок для показа.
 *
 * <p>Порядок объявления = приоритет: сверху то, что полностью ломает печать.
 */
public enum IncidentType {

    /** Нет heartbeat дольше порога — киоск не отвечает. */
    OFFLINE(IncidentSeverity.DOWN, "Нет связи"),

    /** Замятие бумаги — требует физического вмешательства. */
    PAPER_JAM(IncidentSeverity.DOWN, "Замятие бумаги"),

    /** Бумага закончилась. */
    PAPER_OUT(IncidentSeverity.DOWN, "Закончилась бумага"),

    /** Тонер закончился. */
    TONER_EMPTY(IncidentSeverity.DOWN, "Закончился тонер"),

    /** Открыта крышка принтера. */
    DOOR_OPEN(IncidentSeverity.DOWN, "Открыта крышка"),

    /** Принтер не отвечает, хотя киоск на связи. */
    PRINTER_OFFLINE(IncidentSeverity.DOWN, "Принтер не отвечает"),

    /** Драйвер вернул ошибку — текст в reason. */
    PRINTER_ERROR(IncidentSeverity.DOWN, "Ошибка принтера"),

    /** Тонер заканчивается — киоск ещё печатает. */
    TONER_LOW(IncidentSeverity.WARNING, "Заканчивается тонер"),

    /** Бумага заканчивается — киоск ещё печатает. */
    PAPER_LOW(IncidentSeverity.WARNING, "Заканчивается бумага");

    private final IncidentSeverity severity;
    private final String title;

    IncidentType(IncidentSeverity severity, String title) {
        this.severity = severity;
        this.title = title;
    }

    public IncidentSeverity severity() { return severity; }

    public String title() { return title; }

    /** Печать полностью невозможна. */
    public boolean isBlocking() { return severity == IncidentSeverity.DOWN; }
}
