-- Задача 1: блокировка PIN за конкретным киоском после первого успешного verify.
--
-- Семантика:
--   После того как киоск X впервые ввёл PIN и сервер вернул файл, PIN
--   «прикрепляется» к этому киоску на holder_expires_at (now + kiosk.pin.ttl,
--   по умолчанию 10 минут). Пока hold жив, другой киоск Y, введя тот же PIN,
--   получит 423 Locked вместо файла. Сам киоск X может повторно verify-ить
--   свой PIN (пере-ввод, реконнект) — hold при этом продлевается.
--
--   Hold снимается тремя путями:
--     1. Явно — когда юзер возвращается на HOME (клиент дёргает release).
--     2. Неявно — по истечении holder_expires_at (другой киоск снова сможет взять).
--     3. Физически — cleanup-джобом вместе со всей строкой по expires_at.
--
-- Обе колонки nullable: NULL = PIN свободен, никем не удерживается.

ALTER TABLE files ADD COLUMN holder_kiosk_id    VARCHAR(50);
ALTER TABLE files ADD COLUMN holder_expires_at  TIMESTAMPTZ;

-- Консистентность: либо оба поля заданы (активный hold), либо оба NULL (свободен).
ALTER TABLE files ADD CONSTRAINT chk_holder_consistency
    CHECK (
        (holder_kiosk_id IS NULL     AND holder_expires_at IS NULL)
            OR (holder_kiosk_id IS NOT NULL AND holder_expires_at IS NOT NULL)
        );