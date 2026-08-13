-- Очередь команд киоскам (перезагрузка и всё, что появится дальше).
--
-- Команды НЕ доставляются пушем: киоск стоит за NAT в торговом центре,
-- входящее соединение до него не пробить. Вместо этого он сам забирает
-- команду в ответ на очередной heartbeat (раз в 30 секунд).

CREATE TABLE kiosk_commands (
    id             UUID         PRIMARY KEY,
    kiosk_id       VARCHAR(64)  NOT NULL REFERENCES kiosks(id) ON DELETE CASCADE,

    -- RESTART_APP | REBOOT_OS
    type           VARCHAR(32)  NOT NULL,
    -- PENDING | SENT | DONE | FAILED | EXPIRED | CANCELLED
    status         VARCHAR(16)  NOT NULL,

    -- Логин оператора: перезагрузка точки должна быть именной.
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL,
    dispatched_at  TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    result_message TEXT
);

-- Защита от «нажал пять раз, потому что не сработало с первого».
-- Одна ожидающая команда на киоск — остальные отсекаются на уровне БД,
-- а не надеждой на аккуратность оператора.
CREATE UNIQUE INDEX uniq_kiosk_pending_command
    ON kiosk_commands (kiosk_id)
    WHERE status = 'PENDING';

-- История команд точки в админке, свежие сверху.
CREATE INDEX idx_kiosk_commands_history
    ON kiosk_commands (kiosk_id, created_at DESC);
