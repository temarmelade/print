-- ════════════════════════════════════════════════════════════════════════
--  V11: тип операции задания.
--
--  До этого print_jobs хранила только настройки печати и не различала, ЧТО
--  за операция породила запись: обычная печать, ксерокопия, печать скана или
--  цифровая доставка скана (веб/Telegram). Без этого аналитика не могла
--  отделить «печатали» от «сканировали».
--
--  Значения соответствуют enum com.printkiosk.shared.api.OperationType:
--    PRINT              — печать загруженного файла (сайт/Telegram-бот)
--    COPY               — ксерокопия (скан → сразу печать)
--    SCAN_PRINT         — скан → печать через экран действий
--    SCAN_DOWNLOAD_WEB  — скан → получение по ссылке (веб), платно
--    SCAN_SEND_TELEGRAM — скан → получение в Telegram, платно
-- ════════════════════════════════════════════════════════════════════════

ALTER TABLE print_jobs
    ADD COLUMN operation_type VARCHAR(30);

-- Backfill старых записей эвристикой по источнику файла (files.source).
-- Цифровая доставка появилась вместе с этой колонкой, поэтому старых
-- SCAN_DOWNLOAD_WEB / SCAN_SEND_TELEGRAM записей быть не может.
UPDATE print_jobs j
   SET operation_type = CASE f.source
                            WHEN 'COPY' THEN 'COPY'
                            WHEN 'SCAN' THEN 'SCAN_PRINT'
                            ELSE 'PRINT'
                        END
  FROM files f
 WHERE f.id = j.file_id
   AND j.operation_type IS NULL;

-- Файл мог быть удалён по TTL (file_id = NULL) — источник неизвестен,
-- считаем обычной печатью.
UPDATE print_jobs
   SET operation_type = 'PRINT'
 WHERE operation_type IS NULL;

-- С этого момента тип операции обязателен.
ALTER TABLE print_jobs
    ALTER COLUMN operation_type SET NOT NULL;

ALTER TABLE print_jobs
    ADD CONSTRAINT chk_operation_type_valid CHECK (operation_type IN (
        'PRINT', 'COPY', 'SCAN_PRINT', 'SCAN_DOWNLOAD_WEB', 'SCAN_SEND_TELEGRAM'
    ));

-- Аналитика фильтрует/группирует по типу операции — индексируем.
CREATE INDEX idx_print_jobs_operation_type ON print_jobs (operation_type);
