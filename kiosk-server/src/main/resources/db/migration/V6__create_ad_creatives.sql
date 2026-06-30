CREATE TABLE ad_creatives (
                              id               UUID         PRIMARY KEY,
                              title            VARCHAR(120) NOT NULL,
                              media_type       VARCHAR(10)  NOT NULL,
                              slot             VARCHAR(10)  NOT NULL,
                              stored_filename  VARCHAR(80)  NOT NULL UNIQUE,
                              original_filename VARCHAR(255) NOT NULL,
                              content_type     VARCHAR(100) NOT NULL,
                              file_size        BIGINT       NOT NULL,
                              duration_sec     INT,
                              sort_order       INT          NOT NULL DEFAULT 0,
                              enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
                              created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

                              CONSTRAINT chk_ad_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
                              CONSTRAINT chk_ad_slot       CHECK (slot IN ('HOME', 'BANNER')),
                              CONSTRAINT chk_ad_duration   CHECK (
                                  (media_type = 'VIDEO')
                                      OR (media_type = 'IMAGE' AND duration_sec IS NOT NULL AND duration_sec > 0)
                                  )
);

CREATE INDEX idx_ad_creatives_slot_enabled
    ON ad_creatives (slot, enabled, sort_order);