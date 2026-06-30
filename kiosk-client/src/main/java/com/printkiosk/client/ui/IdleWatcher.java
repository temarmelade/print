package com.printkiosk.client.ui;

import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IdleWatcher {

    private final PauseTransition idleTimer;
    private final Runnable onIdle;
    private final Runnable onWake;

    private boolean screensaverActive = false;

    public IdleWatcher(Duration idleTimeout, Runnable onIdle, Runnable onWake) {
        this.onIdle = onIdle;
        this.onWake = onWake;
        this.idleTimer = new PauseTransition(idleTimeout);
        this.idleTimer.setOnFinished(e -> triggerIdle());
    }

    public void attach(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onUserActivity);
        scene.addEventFilter(TouchEvent.TOUCH_PRESSED, this::onUserActivity);
        scene.addEventFilter(KeyEvent.KEY_PRESSED,     this::onUserActivity);
        idleTimer.playFromStart();
    }

    private void onUserActivity(javafx.event.Event event) {
        if (screensaverActive) {
            event.consume();
            wake();
        } else {
            idleTimer.playFromStart();
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
        idleTimer.playFromStart();
    }

    public void reset() {
        if (!screensaverActive) {
            idleTimer.playFromStart();
        }
    }

    public void cancelIdle() {
        screensaverActive = false;
        idleTimer.playFromStart();
    }

    public boolean isScreensaverActive() {
        return screensaverActive;
    }
}