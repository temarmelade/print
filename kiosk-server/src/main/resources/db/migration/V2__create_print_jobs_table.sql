CREATE TABLE print_jobs (
                            id              UUID         PRIMARY KEY,
                            file_id         UUID         NOT NULL,

    -- Print settings
                            copies          INTEGER      NOT NULL CHECK (copies > 0 AND copies <= 100),
                            color_mode      VARCHAR(20)  NOT NULL,
                            double_sided    BOOLEAN      NOT NULL DEFAULT FALSE,
                            orientation     VARCHAR(20)  NOT NULL,
                            paper_size      VARCHAR(20)  NOT NULL,

    -- Pricing
                            price_som       INTEGER      NOT NULL CHECK (price_som >= 0),

    -- Payment
                            payment_id      VARCHAR(100),
                            payment_url     VARCHAR(500),
                            payment_status  VARCHAR(20),

    -- State machine
                            status          VARCHAR(30)  NOT NULL,

    -- Audit
                            kiosk_id        VARCHAR(50),
                            created_at      TIMESTAMPTZ  NOT NULL,
                            paid_at         TIMESTAMPTZ,
                            completed_at    TIMESTAMPTZ,

                            CONSTRAINT fk_print_jobs_file
                                FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,

                            CONSTRAINT chk_status_valid CHECK (status IN (
                                                                          'READY', 'PAYMENT_PENDING', 'PAID', 'PRINTING', 'COMPLETED', 'FAILED'
                                )),
                            CONSTRAINT chk_payment_status_valid CHECK (payment_status IS NULL OR payment_status IN (
                                                                                                                    'PENDING', 'PAID', 'FAILED', 'CANCELLED'
                                ))
);

-- Поиск активного job'а по PIN идёт через JOIN files,
-- индекс по file_id ускоряет джоин.
CREATE        INDEX idx_print_jobs_file_id        ON print_jobs (file_id);

-- Webhook от Finik приходит с payment_id — должен находить job мгновенно.
-- Уникальность защищает от двойной обработки webhook'а.
CREATE UNIQUE INDEX uniq_print_jobs_payment_id    ON print_jobs (payment_id)
    WHERE payment_id IS NOT NULL;

-- Поиск незавершённых job'ов для метрик / cleanup.
CREATE        INDEX idx_print_jobs_status         ON print_jobs (status);
CREATE        INDEX idx_print_jobs_created_at     ON print_jobs (created_at DESC);