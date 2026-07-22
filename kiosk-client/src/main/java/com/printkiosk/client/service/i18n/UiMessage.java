package com.printkiosk.client.service.i18n;

/**
 * Локализуемое сообщение, которым сервисный слой (flow-классы) сообщает UI
 * о статусе или ошибке.
 *
 * <p>Сервисы НЕ должны знать текущий язык и не должны собирать текст сами:
 * иначе строка «замерзает» на языке, который был в момент события, и при
 * переключении языка остаётся старой. Вместо текста flow отдаёт ключ бандла
 * и аргументы, а контроллер рендерит это через LocalizationService в момент
 * показа — и может перерендерить при смене языка.
 *
 * <p>Использование во flow:
 * <pre>{@code
 * listener.onError(UiMessage.of("preview.error.render"));
 * listener.onStatus(UiMessage.of("printing.page.of", page, total));
 * }</pre>
 *
 * <p>Для текстов, у которых ключа нет и быть не может (например, тело ошибки
 * с сервера или {@code exception.getMessage()}), есть {@link #raw(String)} —
 * такое сообщение показывается как есть. Это же позволяет мигрировать flow
 * по одному: необращённые места временно заворачиваются в {@code raw(...)},
 * и ничего не ломается.
 *
 * @param key  ключ в бандле i18n/messages (или {@link #RAW_KEY} для сырого текста)
 * @param args аргументы MessageFormat, подставляемые в {0}, {1}, ...
 */
public record UiMessage(String key, Object... args) {

    /** Служебный ключ-маркер для сообщений с сырым (нелокализуемым) текстом. */
    public static final String RAW_KEY = "@raw";

    /** Локализуемое сообщение: ключ бандла + аргументы MessageFormat. */
    public static UiMessage of(String key, Object... args) {
        return new UiMessage(key, args);
    }

    /**
     * Сырой текст без перевода — для сообщений с сервера, деталей исключений
     * и временных заглушек при поэтапной миграции flow-классов.
     */
    public static UiMessage raw(String text) {
        return new UiMessage(RAW_KEY, text);
    }

    /** true — текст показывается как есть, без обращения к бандлу. */
    public boolean isRaw() {
        return RAW_KEY.equals(key);
    }
}