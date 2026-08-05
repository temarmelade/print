-- ════════════════════════════════════════════════════════════════════════
--  V14: подписчики уведомлений об инцидентах в Telegram.
--
--  Инциденты (V13) уже пишутся, но чтобы их увидеть, нужно открыть админку.
--  Ночью и в выходные так не работает: о вставшем киоске надо узнавать сразу.
--
--  Подписка привязана к chat_id Telegram, а не к админ-аккаунту: техник может
--  не иметь входа в панель, но обязан получать сигнал. Ключ — chat_id, поэтому
--  повторная подписка просто обновляет запись, а не плодит дубли.
--
--  min_severity — фильтр шума. DOWN означает «только когда киоск встал»;
--  WARNING — ещё и «заканчивается бумага». По умолчанию DOWN: предупреждения
--  о расходниках не должны будить ночью.
-- ════════════════════════════════════════════════════════════════════════

CREATE TABLE incident_subscribers (
    chat_id       BIGINT      PRIMARY KEY,          -- Telegram chat id

    -- Кто это: имя/должность для списка в админке.
    label         VARCHAR(120),

    -- DOWN — только блокирующие; WARNING — и предупреждения тоже.
    min_severity  VARCHAR(16) NOT NULL DEFAULT 'DOWN',

    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Когда последний раз успешно доставили сообщение. Если Telegram начал
    -- отвечать 403 (пользователь заблокировал бота), подписку гасим.
    last_sent_at  TIMESTAMPTZ,
    last_error    VARCHAR(200),

    CONSTRAINT chk_subscriber_severity CHECK (min_severity IN ('DOWN', 'WARNING'))
);

CREATE INDEX idx_incident_subscribers_active ON incident_subscribers (active);
