-- ════════════════════════════════════════════════════════════════════════
--  V13: Фаза 3 — история инцидентов киосков.
--
--  Проблема: TelemetryService УЖЕ вычисляет причину проблемы (deriveHealth),
--  но нигде её не сохраняет — kiosk_telemetry перезаписывается каждым
--  heartbeat. В итоге видно только «сейчас сломано», а не «вчера в 14:20
--  кончилась бумага, простояло 40 минут». Ни SLA, ни разбора полётов.
--
--  Ключевое решение схемы — incident_type, а НЕ текст причины, как признак
--  тождества инцидента. Причина содержит динамику («Нет связи более 5 мин»,
--  «Мало бумаги (~30 листов)»): дедупликация по тексту открывала бы новый
--  инцидент на каждый heartbeat. Тип стабилен, текст — лишь снимок для UI.
--
--  Открытый инцидент = resolved_at IS NULL. Частичный уникальный индекс
--  гарантирует, что на киоск+тип открыт максимум один — параллельные
--  heartbeat'ы не создадут дублей.
-- ════════════════════════════════════════════════════════════════════════

CREATE TABLE kiosk_incidents (
    id            BIGSERIAL   PRIMARY KEY,
    kiosk_id      VARCHAR(64) NOT NULL REFERENCES kiosks(id) ON DELETE CASCADE,

    -- Стабильный код проблемы: PAPER_JAM, PAPER_OUT, TONER_EMPTY, DOOR_OPEN,
    -- PRINTER_OFFLINE, PRINTER_ERROR, OFFLINE, TONER_LOW, PAPER_LOW.
    incident_type VARCHAR(32) NOT NULL,

    -- DOWN — печать невозможна; WARNING — работает, но скоро встанет.
    severity      VARCHAR(16) NOT NULL,

    -- Человекочитаемая причина на момент открытия (снимок, может устареть).
    reason        VARCHAR(300),

    started_at    TIMESTAMPTZ NOT NULL,
    resolved_at   TIMESTAMPTZ,              -- NULL = инцидент ещё открыт

    -- Сколько раз состояние подтверждалось heartbeat'ами. Помогает отличить
    -- устойчивую поломку от одиночного всплеска.
    occurrences   INTEGER     NOT NULL DEFAULT 1,
    last_seen_at  TIMESTAMPTZ NOT NULL,

    -- Подтверждение оператором: «увидел, техник выехал».
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by VARCHAR(120),

    CONSTRAINT chk_incident_severity CHECK (severity IN ('DOWN', 'WARNING')),
    CONSTRAINT chk_incident_type_valid CHECK (incident_type IN (
        'PAPER_JAM', 'PAPER_OUT', 'TONER_EMPTY', 'DOOR_OPEN',
        'PRINTER_OFFLINE', 'PRINTER_ERROR', 'OFFLINE',
        'TONER_LOW', 'PAPER_LOW'
    ))
);

-- Не более одного ОТКРЫТОГО инцидента на киоск+тип.
CREATE UNIQUE INDEX uq_kiosk_incident_open
    ON kiosk_incidents (kiosk_id, incident_type)
 WHERE resolved_at IS NULL;

-- Лента инцидентов: свежие сверху.
CREATE INDEX idx_kiosk_incidents_started ON kiosk_incidents (started_at DESC);

-- Быстрый выбор открытых (главный экран Инцидентов).
CREATE INDEX idx_kiosk_incidents_open
    ON kiosk_incidents (kiosk_id, started_at DESC)
 WHERE resolved_at IS NULL;
