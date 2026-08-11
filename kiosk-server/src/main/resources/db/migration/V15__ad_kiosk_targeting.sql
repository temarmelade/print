-- Таргетинг рекламы на киоски.
--
-- Пустой набор строк для креатива = «показывать везде». Это сохраняет
-- поведение всех уже загруженных креативов: миграция не создаёт для них
-- ни одной строки, значит они как крутились на всей сети, так и крутятся.
-- Альтернатива (проставить всем киоскам явную привязку) сломалась бы при
-- добавлении нового киоска — он бы не увидел ни одного старого ролика.

CREATE TABLE ad_creative_kiosks (
    ad_id    UUID        NOT NULL REFERENCES ad_creatives(id) ON DELETE CASCADE,
    kiosk_id VARCHAR(64) NOT NULL REFERENCES kiosks(id)       ON DELETE CASCADE,

    PRIMARY KEY (ad_id, kiosk_id)
);

-- Обратный поиск «что крутить на этом киоске» и каскад при удалении киоска.
CREATE INDEX idx_ad_creative_kiosks_kiosk ON ad_creative_kiosks (kiosk_id);
