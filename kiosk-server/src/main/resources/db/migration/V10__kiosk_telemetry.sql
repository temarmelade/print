-- ════════════════════════════════════════════════════════════════════════
--  V10: Фаза 2 — реестр киосков и телеметрия.
--
--  Ключевой принцип схемы: всё, что принтер МОЖЕТ не отдать, хранится как
--  NULL («неизвестно»), а не как 0. Ноль означал бы «тонер кончился», что
--  неправда — это «принтер не сообщает». Canon MF232w — бытовая модель,
--  и точные уровни расходников она может не отдавать вовсе.
--
--  Реальность железа (Canon MF232w), учтённая здесь:
--    • одна кассета на 250 листов (в ТЗ было два лотка — их нет);
--    • единый картридж 737 (тонер+барабан), ресурс ~2400 страниц;
--    • датчика уровня бумаги, скорее всего, нет → процент считаем программно
--      по счётчику страниц с момента заправки (pages_at_paper_refill).
-- ════════════════════════════════════════════════════════════════════════

-- ── Реестр киосков ──
CREATE TABLE kiosks (
    id              VARCHAR(64)  PRIMARY KEY,          -- совпадает с X-Kiosk-Id
    name            VARCHAR(120) NOT NULL,
    location        VARCHAR(200),
    latitude        DOUBLE PRECISION,                  -- для карты сети
    longitude       DOUBLE PRECISION,

    -- Аутентификация киоска: без неё телеметрию мог бы подделать кто угодно.
    api_key_hash    VARCHAR(100) NOT NULL,

    -- Параметры расходников (под MF232w, но настраиваются на киоск).
    paper_capacity   INTEGER NOT NULL DEFAULT 250,     -- листов в кассете
    cartridge_yield  INTEGER NOT NULL DEFAULT 2400,    -- ресурс картриджа 737

    -- Точки отсчёта для программной оценки расходников.
    -- Счётчик страниц принтера на момент последней заправки/замены.
    pages_at_paper_refill     INTEGER,
    pages_at_cartridge_change INTEGER,
    paper_refilled_at         TIMESTAMPTZ,
    cartridge_changed_at      TIMESTAMPTZ,

    maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE,   -- «киоск временно не работает»
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Текущее состояние киоска (перезаписывается каждым heartbeat) ──
CREATE TABLE kiosk_telemetry (
    kiosk_id        VARCHAR(64) PRIMARY KEY REFERENCES kiosks(id) ON DELETE CASCADE,

    reported_at     TIMESTAMPTZ NOT NULL,              -- когда киоск прислал
    client_version  VARCHAR(40),

    -- Принтер: NULL = «неизвестно», это НЕ ошибка.
    printer_online  BOOLEAN,
    toner_percent   INTEGER,                           -- NULL, если модель не отдаёт
    paper_percent   INTEGER,                           -- обычно оценка, а не датчик
    paper_source    VARCHAR(16),                       -- SENSOR | ESTIMATE | UNKNOWN
    toner_source    VARCHAR(16),                       -- SENSOR | ESTIMATE | UNKNOWN

    -- Грубые состояния — их отдаёт даже бытовой принтер (hrPrinterDetectedErrorState).
    paper_out       BOOLEAN NOT NULL DEFAULT FALSE,
    paper_jam       BOOLEAN NOT NULL DEFAULT FALSE,
    toner_low       BOOLEAN NOT NULL DEFAULT FALSE,
    toner_empty     BOOLEAN NOT NULL DEFAULT FALSE,
    door_open       BOOLEAN NOT NULL DEFAULT FALSE,
    printer_error   VARCHAR(200),                      -- свободный текст от драйвера

    page_counter    INTEGER,                           -- prtMarkerLifeCount, если есть

    CONSTRAINT chk_toner_pct CHECK (toner_percent IS NULL OR (toner_percent BETWEEN 0 AND 100)),
    CONSTRAINT chk_paper_pct CHECK (paper_percent IS NULL OR (paper_percent BETWEEN 0 AND 100))
);

CREATE INDEX idx_kiosk_telemetry_reported ON kiosk_telemetry (reported_at);

-- ── История телеметрии: нужна для предиктивной аналитики из ТЗ ──
--    («при текущем темпе бумага кончится завтра к 14:30»)
CREATE TABLE kiosk_telemetry_history (
    id            BIGSERIAL PRIMARY KEY,
    kiosk_id      VARCHAR(64) NOT NULL REFERENCES kiosks(id) ON DELETE CASCADE,
    recorded_at   TIMESTAMPTZ NOT NULL,
    toner_percent INTEGER,
    paper_percent INTEGER,
    page_counter  INTEGER
);

CREATE INDEX idx_telemetry_history_kiosk_time
    ON kiosk_telemetry_history (kiosk_id, recorded_at DESC);
