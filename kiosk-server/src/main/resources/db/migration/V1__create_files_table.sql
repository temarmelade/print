CREATE TABLE files (
                       id                UUID         PRIMARY KEY,
                       code              VARCHAR(4)   NOT NULL,
                       stored_filename   VARCHAR(80)  NOT NULL,
                       original_filename VARCHAR(255) NOT NULL,
                       content_type      VARCHAR(100) NOT NULL,
                       file_size         BIGINT       NOT NULL CHECK (file_size > 0),
                       source            VARCHAR(20)  NOT NULL,
                       telegram_user_id  BIGINT,
                       created_at        TIMESTAMPTZ  NOT NULL,
                       expires_at        TIMESTAMPTZ  NOT NULL,
                       consumed_at       TIMESTAMPTZ,

                       CONSTRAINT chk_code_format CHECK (code ~ '^[0-9]{4}$'),
    CONSTRAINT chk_expires_after_created CHECK (expires_at > created_at)
);

-- Уникальность PIN среди активных кодов обеспечивается тем, что
-- cleanup-джоб физически удаляет истёкшие записи (см. пункт 4).
-- Поэтому простой UNIQUE — это и есть "уникальность среди живых".
CREATE UNIQUE INDEX uniq_files_code            ON files (code);
CREATE UNIQUE INDEX uniq_files_stored_filename ON files (stored_filename);
CREATE        INDEX idx_files_expires_at       ON files (expires_at);