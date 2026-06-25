package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.api.PinLockedException;
import com.printkiosk.client.api.PinNotFoundException;
import com.printkiosk.client.api.ServerUnavailableException;
import com.printkiosk.shared.api.dto.VerifyResponse;
import javafx.application.Platform;
import javafx.concurrent.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Управляет состоянием экрана ввода PIN: накапливает цифры,
 * отправляет запрос {@code server.verify(pin)} и уведомляет UI
 * о результате через переданный listener.
 * <p>
 * Сам сервис ничего не знает про JavaFX-узлы — он лишь оркеструет
 * накопление PIN и сетевой вызов. UI получает события и обновляет
 * экран в своём коде (в {@link com.printkiosk.client.ui.controller.MainController}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinEntryFlow {

    public static final int PIN_LENGTH = 4;

    private final KioskServerClient server;

    private final StringBuilder buffer = new StringBuilder();
    private Listener listener;
    private boolean requestInFlight;

    // ════════════════════════════════════════════════════════════════
    //  Public API — вызывается из MainController
    // ════════════════════════════════════════════════════════════════

    /** Привязать UI-слушатель. Передавай {@code null}, чтобы отвязать (например, при уходе со экрана). */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Сбросить состояние (например, при возврате на главный экран). */
    public void reset() {
        buffer.setLength(0);
        requestInFlight = false;
        notifyBufferChanged();
    }

    public void pressDigit(String digit) {
        if (requestInFlight) return;                      // блокировка ввода пока летит запрос
        if (buffer.length() >= PIN_LENGTH) return;         // лимит длины
        if (digit == null || digit.length() != 1) return;
        if (!Character.isDigit(digit.charAt(0)))   return;

        buffer.append(digit);
        notifyBufferChanged();
    }

    public void pressBackspace() {
        if (requestInFlight) return;
        if (buffer.isEmpty())  return;
        buffer.deleteCharAt(buffer.length() - 1);
        notifyBufferChanged();
    }

    /**
     * Отправить накопленный PIN на сервер. Если PIN неполный,
     * слушатель получит {@link Listener#onShortPin()} и больше ничего.
     */
    public void submit() {
        if (requestInFlight) return;
        if (buffer.length() < PIN_LENGTH) {
            notifyShortPin();
            return;
        }

        final String pin = buffer.toString();
        requestInFlight = true;
        notifyLoading();

        Task<VerifyResponse> task = new Task<>() {
            @Override
            protected VerifyResponse call() {
                return server.verify(pin);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            requestInFlight = false;
            VerifyResponse response = task.getValue();
            log.info("PIN verified, file={} ({} bytes)",
                    response.originalFilename(), response.fileSize());
            notifySuccess(pin, task.getValue());
            buffer.setLength(0);   // очищаем после успеха
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            requestInFlight = false;
            Throwable cause = task.getException();
            if (cause instanceof PinNotFoundException) {
                log.info("PIN {} not found / expired", maskPin(pin));
                notifyPinNotFound();
            } else if (cause instanceof PinLockedException) {
                log.info("PIN {} locked by another kiosk", maskPin(pin));
                notifyPinLocked();
            } else if (cause instanceof ServerUnavailableException) {
                log.warn("Server unavailable during verify", cause);
                notifyServerUnavailable();
            } else {
                log.error("Unexpected error during verify", cause);
                notifyServerUnavailable();
            }
            buffer.setLength(0);
        }));

        Thread worker = new Thread(task, "pin-verify");
        worker.setDaemon(true);
        worker.start();
    }

    public String currentBuffer() {
        return buffer.toString();
    }
    // ════════════════════════════════════════════════════════════════
    //  Listener — события для UI
    // ════════════════════════════════════════════════════════════════

    public interface Listener {
        /** Содержимое буфера изменилось (нажали цифру/backspace/сбросили). */
        void onBufferChanged(String currentBuffer);

        /** Юзер нажал submit, но PIN ещё не из {@value PIN_LENGTH} цифр. */
        void onShortPin();

        /** Запрос ушёл на сервер — показать «Проверяем...» / спиннер. */
        void onLoading();

        /** PIN валиден, файл найден. */
        void onSuccess(String pin, VerifyResponse response);

        /** PIN не найден или истёк. */
        void onPinNotFound();

        /** PIN валиден, но удерживается другим киоском (423 Locked). */
        void onPinLocked();

        /** Сервер недоступен (таймаут/сеть/5xx). */
        void onServerUnavailable();
    }

    // ════════════════════════════════════════════════════════════════
    //  Private helpers
    // ════════════════════════════════════════════════════════════════

    private void notifyBufferChanged() {
        if (listener != null) listener.onBufferChanged(buffer.toString());
    }
    private void notifyShortPin()          { if (listener != null) listener.onShortPin(); }
    private void notifyLoading()           { if (listener != null) listener.onLoading(); }
    private void notifySuccess(String pin, VerifyResponse r) {
        if (listener != null) listener.onSuccess(pin, r);
    }
    private void notifyPinNotFound()       { if (listener != null) listener.onPinNotFound(); }
    private void notifyPinLocked()         { if (listener != null) listener.onPinLocked(); }
    private void notifyServerUnavailable() { if (listener != null) listener.onServerUnavailable(); }

    private static String maskPin(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }
}