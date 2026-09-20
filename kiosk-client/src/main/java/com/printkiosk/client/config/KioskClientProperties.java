package com.printkiosk.client.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "kiosk")
public class KioskClientProperties {

    @NotNull
    private final Printer printer = new Printer();

    @Getter
    @Setter
    public static class Printer {
        /** Имя принтера в системе (PrintServiceLookup). Пусто → системный default. */
        private String name;

        /** SNMP-хост принтера. Пусто → SNMP мониторинг отключён (USB-режим). */
        private String snmpHost;

        /**
         * Версия SNMP: "v1" или "v2c". Canon MF232w заявляет SNMPv1/v3 —
         * на v2c может не ответить, поэтому по умолчанию v1.
         */
        private String snmpVersion = "v1";

        @Min(1)
        private int snmpPort = 161;

        private String snmpCommunity = "public";

        /** Период опроса SNMP, мс. */
        @Min(1000)
        private long snmpPollIntervalMs = 30_000;
    }

    @NotNull
    private final Upload upload = new Upload();

    @Getter
    @Setter
    public static class Upload {
        /** Базовый deep-link Telegram-бота, напр. https://t.me/PrintKioskBot */
        private String telegramBotUrl = "https://t.me/AlaTooPrintKioskBot";

        /**
         * Ручная ссылка на страницу загрузки. Обычно НЕ задаётся: ссылку для
         * QR выдаёт сервер (GET /api/kiosk/upload-link). Используется только
         * как запасная, пока сервер не ответил. Пусто — запасная строится из
         * kiosk.server.public-base-url.
         */
        private String webUrl = "";
    }
}
