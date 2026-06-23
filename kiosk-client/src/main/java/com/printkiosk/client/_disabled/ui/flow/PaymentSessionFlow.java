package com.printkiosk.client.ui.flow;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.shared.api.dto.PaymentSessionDto;
import com.printkiosk.shared.api.dto.PaymentStatusDto;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Клиентский оркестратор платёжной сессии.
 * <p>
 * В отличие от старого {@code PaymentFlow}, здесь нет публичного
 * {@code notifyPaymentSucceeded(pin)} — webhook теперь приходит
 * на сервер, и единственный способ узнать об оплате на киоске —
 * polling {@code GET /api/payments/{pin}/status}.
 * <p>
 * Идемпотентность перехода и проверка «актуальный ли это PIN»
 * остаются здесь — это UI-инвариант, не серверный.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSessionFlow {

    private static final int POLLING_INTERVAL_SECONDS = 3;

    private final KioskServerClient server;

    private String currentPin;
    private PaymentSessionListener listener;
    private Timeline pollingTimer;
    private boolean transitionDone;

    /** Запускает сессию: создаёт платёж на сервере, ловит ответ, начинает polling. */
    public void startSession(String pin, int amountSom, PaymentSessionListener listener) {
        stopSession();

        this.currentPin     = pin;
        this.listener       = listener;
        this.transitionDone = false;

        listener.onSessionLoading();

        Task<PaymentSessionDto> task = new Task<>() {
            @Override
            protected PaymentSessionDto call() {
                // RestClient/WebClient, синхронный вызов сервера
                return server.createPayment(pin, amountSom);
            }
        };

        task.setOnSucceeded(e -> {
            if (!pin.equals(currentPin) || this.listener == null) return;
            PaymentSessionDto session = task.getValue();
            log.info("Payment session created: id={}, PIN={}", session.paymentId(), maskPin(pin));
            this.listener.onSessionReady(session.paymentUrl());
            startPolling();
        });

        task.setOnFailed(e -> {
            if (!pin.equals(currentPin) || this.listener == null) return;
            log.error("Could not start payment for PIN={}", maskPin(pin), task.getException());
            this.listener.onSessionFailed(task.getException());
        });

        Thread worker = new Thread(task, "kiosk-payment-worker");
        worker.setDaemon(true);
        worker.start();
    }

    public void stopSession() {
        stopPolling();
        currentPin = null;
        listener = null;
    }

    // ---- Polling ----

    private void startPolling() {
        stopPolling();
        pollingTimer = new Timeline(new KeyFrame(
                Duration.seconds(POLLING_INTERVAL_SECONDS),
                e -> pollOnce()
        ));
        pollingTimer.setCycleCount(Timeline.INDEFINITE);
        pollingTimer.play();
    }

    private void stopPolling() {
        if (pollingTimer != null) {
            pollingTimer.stop();
            pollingTimer = null;
        }
    }

    private void pollOnce() {
        if (transitionDone || currentPin == null) return;
        final String pin = currentPin;

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                PaymentStatusDto dto = server.getPaymentStatus(pin);
                if (dto == null || dto.status() == null) return false;
                String s = dto.status();
                return "PAID".equalsIgnoreCase(s) || "PRINTING".equalsIgnoreCase(s);
            }
        };

        task.setOnSucceeded(e -> {
            if (Boolean.TRUE.equals(task.getValue())) {
                log.info("Polling detected PAID for PIN={}", maskPin(pin));
                handleSuccess(pin);
            }
        });
        task.setOnFailed(e -> log.warn("Polling failed for PIN={}", maskPin(pin),
                task.getException()));

        Thread t = new Thread(task, "kiosk-payment-poller");
        t.setDaemon(true);
        t.start();
    }

    private void handleSuccess(String paidPin) {
        Platform.runLater(() -> {
            if (transitionDone) return;
            if (paidPin == null || !paidPin.equals(currentPin)) return;
            if (listener == null) return;

            transitionDone = true;
            stopPolling();

            PaymentSessionListener captured = listener;
            try {
                captured.onPaymentSucceeded(paidPin);
            } catch (Exception ex) {
                log.error("Listener threw on onPaymentSucceeded", ex);
            }
        });
    }

    private static String maskPin(String pin) {
        return (pin == null || pin.length() < 2) ? "****" : pin.substring(0, 2) + "**";
    }
}
