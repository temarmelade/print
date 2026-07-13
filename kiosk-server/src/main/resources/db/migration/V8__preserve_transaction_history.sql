-- ════════════════════════════════════════════════════════════════════════
--  V8: транзакции переживают удаление файла.
--
--  Проблема: print_jobs.file_id имел ON DELETE CASCADE на files. Файлы
--  чистятся по TTL (ExpiredFileCleanupJob), и вместе с файлом Postgres молча
--  удалял запись об оплате. Через ~10 минут после платежа финансовая история
--  исчезала: ни выручки, ни аналитики, ни разбора спорных ситуаций.
--
--  Решение: сохранить в самой транзакции всё, что нужно для истории (PIN, имя
--  файла, число страниц), а связь с files оставить только на время жизни файла
--  (она нужна для скачивания при печати) — но уже с ON DELETE SET NULL.
-- ════════════════════════════════════════════════════════════════════════

-- 1. Снимок данных файла внутри транзакции (переживёт удаление files).
ALTER TABLE print_jobs
    ADD COLUMN pin        VARCHAR(4),
    ADD COLUMN file_name  VARCHAR(255),
    ADD COLUMN page_count INTEGER NOT NULL DEFAULT 0;

-- 2. Backfill: заполняем снимок для уже существующих заданий.
UPDATE print_jobs j
   SET pin        = f.code,
       file_name  = f.original_filename,
       page_count = f.page_count
  FROM files f
 WHERE f.id = j.file_id;

-- 3. Снимаем каскад: удаление файла больше НЕ уносит транзакцию.
ALTER TABLE print_jobs DROP CONSTRAINT fk_print_jobs_file;
ALTER TABLE print_jobs ALTER COLUMN file_id DROP NOT NULL;

ALTER TABLE print_jobs
    ADD CONSTRAINT fk_print_jobs_file
        FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE SET NULL;

-- 4. Поиск в админке идёт по PIN — индексируем.
CREATE INDEX idx_print_jobs_pin ON print_jobs (pin);

-- 5. Попутно: в enum PrintJobStatus есть EXPIRED, но в CHECK его не было —
--    попытка записать такой статус упала бы с нарушением ограничения.
ALTER TABLE print_jobs DROP CONSTRAINT chk_status_valid;
ALTER TABLE print_jobs
    ADD CONSTRAINT chk_status_valid CHECK (status IN (
        'READY', 'PAYMENT_PENDING', 'PAID', 'PRINTING', 'COMPLETED', 'FAILED', 'EXPIRED'
    ));
