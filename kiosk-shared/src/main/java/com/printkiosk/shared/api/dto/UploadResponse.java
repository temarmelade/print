package com.printkiosk.shared.api.dto;

import java.time.Instant;

public record UploadResponse(
        String  pin,
        Instant expiresAt,
        long    ttlSeconds,
        /**
         * Готовая ссылка для скачивания с телефона — с одноразовым токеном.
         * Собирается на сервере, а не на киоске: раньше киоск склеивал её
         * из PIN, и секретом было четырёхзначное число.
         */
        String  downloadUrl
) {}
