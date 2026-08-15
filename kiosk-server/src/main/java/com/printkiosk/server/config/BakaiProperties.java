package com.printkiosk.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки OpenBanking API Bakai.
 *
 * <p>В отличие от Finik здесь нет ни подписи запросов, ни приватного ключа:
 * авторизация обычная — логин/пароль в обмен на JWT.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "bakai")
public class BakaiProperties {

    /** Включён ли шлюз. На local-профиле false → работает mock. */
    private boolean enabled = false;

    private String baseUrl = "https://openbanking-api.bakai.kg";

    private String login;
    private String password;

    /** Счёт зачисления — на него приходят деньги за печать. */
    private String accountNo;

    /** Код валюты (сом). Числовой, как требует API Bakai. */
    private int currencyId = 417;

    /**
     * Время жизни QR. Должно быть не меньше, чем живёт платёжная сессия
     * на киоске, иначе человек увидит на экране код, который банк уже
     * считает просроченным.
     */
    private int qrTtl = 15;

    /**
     * Единица измерения TTL. В API это ЧИСЛОВОЙ enum QrTtlUnits, а не строка:
     * 0=Seconds, 1=Minutes, 2=Hours, 3=Days, 4=Months, 5=Years.
     * Значение по умолчанию — минуты.
     */
    private int qrTtlUnits = 1;

    /**
     * Тип кастомного QR для запроса статуса. Значение выдаёт банк —
     * в публичной спецификации допустимые варианты не перечислены.
     */
    private String qrType;

    /**
     * Запас перед истечением токена, за который его пора обновить.
     * Реальный срок жизни JWT сервер узнаёт из самого токена.
     */
    private int tokenRefreshSkewSec = 60;

    /** Как часто опрашивать статус незавершённых платежей. */
    private long pollIntervalMs = 3000;

    /**
     * Сколько опрашивать один платёж, прежде чем бросить. Должно
     * перекрывать qrTtl: смысла спрашивать про просроченный QR нет.
     */
    private long pollTimeoutMin = 20;
}