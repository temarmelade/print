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
        String  downloadUrl,
        /**
         * Ссылка на получение скана в Telegram-боте (t.me/<бот>?start=get_<токен>).
         * Тоже собирается на сервере и тоже с токеном, а не с PIN.
         * null — бот на сервере выключен.
         */
        String  telegramUrl
) {}
