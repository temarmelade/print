package com.printkiosk.client.ui.state;

import java.util.Locale;

/**
 * Языки терминала. isoCode — единый код, который используется:
 * 1) для выбора ResourceBundle (messages_ru / messages_ky / messages_en);
 * 2) в payload QR-кодов (t.me/...?start=lang_ru, .../upload?lang=ru).
 *
 * ВНИМАНИЕ: кыргызский — это "ky" (ISO 639-1), а не "kg" (kg — код страны).
 * Файл messages_kg.properties из монолита нужно переименовать в messages_ky.properties.
 */
public enum Language {
    RU("ru"),
    KY("ky"),
    EN("en");

    private final String isoCode;

    Language(String isoCode) {
        this.isoCode = isoCode;
    }

    public String isoCode() {
        return isoCode;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(isoCode);
    }
}
