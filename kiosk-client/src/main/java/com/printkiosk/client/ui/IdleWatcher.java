package com.printkiosk.client.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Следит за бездействием и отсчитывает время до сброса сессии.
 *
 * <p>Вместо {@link javafx.animation.PauseTransition} здесь посекундный
 * {@link Timeline}: он не только срабатывает по истечении времени, но и
 * даёт остаток, который показывается на экране. Без этого человек не
 * понимает, почему киоск вдруг вернулся на главный экран.
 *
 * <p>Тайм-аут меняется на ходу: на экране оплаты он должен быть заметно
 * длиннее — человек достаёт телефон, ищет приложение банка, вводит код.
 * Минуты там категорически мало.
 */
@Slf4j
public class IdleWatcher {

    private final Timeline ticker;
    private final Runnable onIdle;
    private final Runnable onWake;

    /** Сколько секунд осталось до сброса. Для отображения на экране. */
    private final ReadOnlyIntegerWrapper remainingSeconds = new ReadOnlyIntegerWrapper();

    /** Текущий тайм-аут в секундах — на него сбрасывается отсчёт. */
    private int timeoutSeconds;

    private boolean screensaverActive = false;

    public IdleWatcher(Duration idleTimeout, Runnable onIdle, Runnable onWake) {
        this.onIdle = onIdle;
        this.onWake = onWake;
        this.timeoutSeconds = (int) Math.round(idleTimeout.toSeconds());
        this.remainingSeconds.set(timeoutSeconds);

        this.ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        this.ticker.setCycleCount(Animation.INDEFINITE);
    }

    public ReadOnlyIntegerProperty remainingSecondsProperty() {
        return remainingSeconds.getReadOnlyProperty();
    }

    /**
     * Меняет тайм-аут и начинает отсчёт заново.
     * Вызывается при каждой смене экрана.
     */
    public void setTimeout(java.time.Duration timeout) {
        int seconds = (int) timeout.toSeconds();
        if (seconds == timeoutSeconds) {
            reset();
            return;
        }
        timeoutSeconds = seconds;
        reset();
    }

    public void attach(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onUserActivity);
        scene.addEventFilter(TouchEvent.TOUCH_PRESSED, this::onUserActivity);
        scene.addEventFilter(KeyEvent.KEY_PRESSED,     this::onUserActivity);
        reset();
    }

    private void tick() {
        int left = remainingSeconds.get() - 1;
        remainingSeconds.set(Math.max(0, left));
        if (left <= 0) {
            ticker.stop();
            triggerIdle();
        }
    }

    private void onUserActivity(javafx.event.Event event) {
        if (screensaverActive) {
            event.consume();
            wake();
        } else {
            reset();
        }
    }

    private void triggerIdle() {
        if (screensaverActive) return;
        screensaverActive = true;
        onIdle.run();
    }

    private void wake() {
        if (!screensaverActive) return;
        screensaverActive = false;
        onWake.run();
        reset();
    }

    /** Начать отсчёт заново с полного тайм-аута. */
    public void reset() {
        if (screensaverActive) return;
        remainingSeconds.set(timeoutSeconds);
        ticker.playFromStart();
    }

    public void cancelIdle() {
        screensaverActive = false;
        reset();
    }

    public boolean isScreensaverActive() {
        return screensaverActive;
    }
}
