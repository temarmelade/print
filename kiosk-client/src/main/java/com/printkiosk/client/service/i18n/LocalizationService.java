package com.printkiosk.client.service.i18n;

import com.printkiosk.client.ui.state.Language;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Labeled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Единая точка правды о языке интерфейса киоска.
 *
 * <p>Паттерн: вместо ручного Observer'а используем систему свойств JavaFX.
 * Сервис публикует {@code languageProperty()}; контроллеры один раз в
 * {@code initialize()} биндят тексты через {@link #bind(String)} — и при
 * смене языка ВСЕ открытые экраны обновляются автоматически, без
 * перезагрузки FXML и без методов вида updateTexts().
 *
 * <p>Жизненный цикл на киоске: язык живёт в рамках одной клиентской сессии.
 * {@link #resetToDefault()} обязан вызываться из той же точки, где киоск
 * возвращается в IDLE (inactivity-таймаут или успешная печать).
 *
 * <p>Потокобезопасность: смена языка всегда применяется на FX-потоке,
 * т.к. от неё каскадом обновляются UI-ноды. Таймеры/фоновые потоки могут
 * дёргать {@link #resetToDefault()} безопасно.
 */
@Slf4j
@Component
public class LocalizationService {

    /** База бандла: src/main/resources/i18n/messages[_xx].properties. */
    private static final String BUNDLE_BASE = "i18n.messages";

    /** Язык по умолчанию для терминала. */
    public static final Language DEFAULT_LANGUAGE = Language.RU;

    private final ReadOnlyObjectWrapper<Language> language =
            new ReadOnlyObjectWrapper<>(this, "language", DEFAULT_LANGUAGE);

    /**
     * Текущий бандл. volatile: читается из биндингов на FX-потоке,
     * а resetToDefault() может прийти из потока таймера.
     */
    private volatile ResourceBundle bundle = loadBundle(DEFAULT_LANGUAGE);

    // ===================== ПУБЛИЧНОЕ API =====================

    /** Свойство для листенеров, которым нужен побочный эффект (QR-коды и т.п.). */
    public ReadOnlyObjectProperty<Language> languageProperty() {
        return language.getReadOnlyProperty();
    }

    public Language getLanguage() {
        return language.get();
    }

    /**
     * Смена языка. Порядок важен: сначала подменяем бандл, затем
     * триггерим свойство — чтобы все биндинги при пересчёте уже
     * читали переводы нового языка.
     */
    public void setLanguage(Language lang) {
        if (lang == null || lang == language.get()) {
            return;
        }
        Runnable change = () -> {
            bundle = loadBundle(lang);
            language.set(lang); // одно событие → пересчёт всех биндингов
            log.info("Язык интерфейса переключён: {}", lang);
        };
        if (Platform.isFxApplicationThread()) {
            change.run();
        } else {
            Platform.runLater(change);
        }
    }

    /** Сброс на язык по умолчанию — вызывать при завершении сессии. */
    public void resetToDefault() {
        setLanguage(DEFAULT_LANGUAGE);
    }

    /** Перевод по ключу. Отсутствующий ключ не роняет киоск. */
    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            log.warn("Нет перевода для ключа '{}' (язык {})", key, language.get());
            return "!" + key + "!"; // видно на экране при тестировании, но не краш
        }
    }

    /** Перевод с параметрами: get("print.pages", 5) → "Страниц: 5". */
    public String get(String key, Object... args) {
        return MessageFormat.format(get(key), args);
    }

    // ===================== БИНДИНГИ =====================

    /**
     * Реактивная строка для статических текстов:
     * label.textProperty().bind(loc.bind("home.title"));
     */
    public StringBinding bind(String key) {
        return Bindings.createStringBinding(() -> get(key), language.getReadOnlyProperty());
    }

    /**
     * Реактивная строка с динамическими аргументами. Пересчитывается
     * и при смене языка, и при изменении любого аргумента:
     *
     * totalLabel.textProperty().bind(
     *     loc.bind("price.total", kioskState.totalAmountProperty()));
     */
    public StringBinding bind(String key, ObservableValue<?>... args) {
        Observable[] deps = new Observable[args.length + 1];
        deps[0] = language.getReadOnlyProperty();
        System.arraycopy(args, 0, deps, 1, args.length);
        return Bindings.createStringBinding(
                () -> get(key, Arrays.stream(args).map(ObservableValue::getValue).toArray()),
                deps);
    }

    /** Шорткат, чтобы initialize() контроллера не распухал. */
    public void bindText(Labeled node, String key) {
        node.textProperty().bind(bind(key));
    }

    // ===================== ВНУТРЕННЕЕ =====================

    private static ResourceBundle loadBundle(Language lang) {
        // Java 17 читает .properties-бандлы как UTF-8 — кириллица без uXXXX.
        return ResourceBundle.getBundle(BUNDLE_BASE, lang.toLocale());
    }
}
