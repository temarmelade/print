CREATE TABLE tariffs (
                         id              UUID         PRIMARY KEY,
                         kiosk_id        VARCHAR(50),                                          -- null = глобальный default
                         bw_price_som    INTEGER      NOT NULL CHECK (bw_price_som    >= 0),
                         color_price_som INTEGER      NOT NULL CHECK (color_price_som >= 0),
                         effective_from  TIMESTAMPTZ  NOT NULL,
                         effective_to    TIMESTAMPTZ,
                         created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

                         CONSTRAINT chk_effective_range
                             CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Поиск действующего тарифа: сначала по kiosk_id, потом по NULL (fallback на дефолт).
CREATE INDEX idx_tariffs_kiosk_effective
    ON tariffs (kiosk_id, effective_from DESC);

-- Гарантия что для одного kiosk_id одновременно действует ровно один тариф
-- (если effective_to=NULL — он считается "текущим" и должен быть один).
CREATE UNIQUE INDEX uniq_tariffs_current_per_kiosk
    ON tariffs (COALESCE(kiosk_id, '__default__'))
    WHERE effective_to IS NULL;

-- Стартовый дефолтный тариф. На него ссылается любой kiosk_id, для которого
-- нет своей строки. После добавления реальных Kiosk-конфигов в платформенной
-- фазе будем переопределять per-kiosk.
INSERT INTO tariffs (id, kiosk_id, bw_price_som, color_price_som, effective_from)
VALUES (gen_random_uuid(), NULL, 5, 15, NOW());