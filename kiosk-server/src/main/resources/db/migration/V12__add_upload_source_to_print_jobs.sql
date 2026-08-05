-- ════════════════════════════════════════════════════════════════════════
--  V12: снимок источника загрузки и типа документа в транзакции.
--
--  Проблема: source и content_type живут в files, а files физически удаляются
--  по TTL (ExpiredFileCleanupJob). Через ~10 минут после печати уже нельзя
--  сказать, пришёл документ из Telegram или с сайта и какого он был формата —
--  аналитика по каналам и форматам была принципиально недоступна.
--
--  Решение то же, что в V8 для имени файла и числа страниц: сохранить нужное
--  снимком в самой транзакции. Связь с files остаётся только на время жизни
--  файла (ON DELETE SET NULL).
--
--  upload_source — значения enum UploadSource:
--    TELEGRAM, WEBSITE — документ загружен пользователем извне
--    SCAN, COPY        — документ создан на киоске сканером
--    UNKNOWN           — источник не определён
--
--  file_extension хранится отдельно от content_type: браузеры и Telegram
--  часто присылают octet-stream, и тогда расширение — единственный признак
--  формата. Для аналитики нужнее «pdf/docx/jpg», а не MIME.
-- ════════════════════════════════════════════════════════════════════════

ALTER TABLE print_jobs
    ADD COLUMN upload_source  VARCHAR(20),
    ADD COLUMN content_type   VARCHAR(100),
    ADD COLUMN file_extension VARCHAR(16);

-- Backfill из ещё живых файлов (у удалённых по TTL file_id уже NULL).
UPDATE print_jobs j
   SET upload_source = f.source,
       content_type  = f.content_type
  FROM files f
 WHERE f.id = j.file_id
   AND j.upload_source IS NULL;

-- Расширение достаём из имени файла: оно сохранено снимком ещё с V8,
-- поэтому доступно даже там, где сам файл уже удалён.
UPDATE print_jobs
   SET file_extension = LOWER(SUBSTRING(file_name FROM '\.([A-Za-z0-9]{1,15})$'))
 WHERE file_extension IS NULL
   AND file_name IS NOT NULL;

-- Источник неизвестен там, где файл уже удалён: честнее UNKNOWN, чем
-- гадать. Для сканов/копий источник можно восстановить по типу операции —
-- он был проставлен миграцией V11.
UPDATE print_jobs
   SET upload_source = CASE operation_type
                           WHEN 'COPY'       THEN 'COPY'
                           WHEN 'SCAN_PRINT' THEN 'SCAN'
                           WHEN 'SCAN_DOWNLOAD_WEB'  THEN 'SCAN'
                           WHEN 'SCAN_SEND_TELEGRAM' THEN 'SCAN'
                           ELSE 'UNKNOWN'
                       END
 WHERE upload_source IS NULL;

ALTER TABLE print_jobs
    ALTER COLUMN upload_source SET NOT NULL;

ALTER TABLE print_jobs
    ADD CONSTRAINT chk_upload_source_valid CHECK (upload_source IN (
        'TELEGRAM', 'WEBSITE', 'SCAN', 'COPY', 'UNKNOWN'
    ));

-- Аналитика группирует по этим полям — индексируем.
CREATE INDEX idx_print_jobs_upload_source  ON print_jobs (upload_source);
CREATE INDEX idx_print_jobs_file_extension ON print_jobs (file_extension);
