package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.api.PaymentStreamClient;
import com.printkiosk.client.api.ServerUnavailableException;
import com.printkiosk.shared.api.dto.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.util.UUID;

/**
 * Управляет всем циклом оплаты: createJob → createPayment → SSE-стрим
 * → событие PAID/FAILED → уведомление UI. Таймер 5 минут параллельно
 * крутит обратный отсчёт.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSessionFlow {

    private static final java.time.Duration TIMEOUT     = java.time.Duration.ofMinutes(5);
    private static final int TIMER_TICK_SECONDS         = 1;

    private final KioskServerClient    server;
    private final PaymentStreamClient  streamClient;

    private Listener listener;
    private String   currentPin;
    private UUID     currentJobId;
    private String   currentPaymentId;

    private Closeable sseSubscription;
    private Timeline  countdownTimer;
    private int       secondsLeft;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ════════════════════════════════════════════════════════════════
    //  Public: запуск
    // ════════════════════════════════════════════════════════════════

    /**
     * Запускает цикл: создаёт job, создаёт платёжную сессию, подписывается
     * на SSE. UI получает события через {@link Listener}.
     */
    public void start(String pin, PrintSettings settings) {
        start(pin, settings, null);
    }

    public void start(String pin, PrintSettings settings, java.util.List<Integer> pages) {
        stop();   // на всякий случай — закрываем предыдущую сессию

        this.currentPin = pin;
        notifyLoading();

        Task<PaymentSessionDto> task = new Task<>() {
            @Override
            protected PaymentSessionDto call() {
                // Step 1: создать job
                JobResponse job = server.createJob(new CreateJobRequest(pin, settings, pages));
                currentJobId = job.id();
                log.info("Job created: id={} priceSom={}", job.id(), job.priceSom());

                // Step 2: создать платёжную сессию
                PaymentSessionDto session = server.createPayment(job.id());
                currentPaymentId = session.paymentId();
                log.info("Payment session created: paymentId={}", session.paymentId());

                return session;
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            PaymentSessionDto session = task.getValue();
            notifySessionReady(session);
            subscribeToEvents(pin);
            startCountdown();
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            Throwable cause = task.getException();
            String message = (cause instanceof ServerUnavailableException)
                    ? "Сервер недоступен. Попробуйте позже."
                    : "Не удалось создать платёжную сессию.";
            log.error("Failed to start payment session", cause);
            notifyError(message);
        }));

        Thread t = new Thread(task, "payment-start");
        t.setDaemon(true);
        t.start();
    }

    /** Закрывает текущую сессию (отписка от SSE, остановка таймера). */
    public void stop() {
        if (sseSubscription != null) {
            try { sseSubscription.close(); }
            catch (Exception e) { log.debug("SSE close error: {}", e.getMessage()); }
            sseSubscription = null;
        }
        if (countdownTimer != null) {
            countdownTimer.stop();
            countdownTimer = null;
        }
        currentPin = null;
        currentJobId = null;
        currentPaymentId = null;
    }

    public UUID currentJobId() {
        return currentJobId;
    }

    // ════════════════════════════════════════════════════════════════
    //  SSE subscription
    // ════════════════════════════════════════════════════════════════

    private void subscribeToEvents(String pin) {
        sseSubscription = streamClient.connect(
                pin,
                event -> Platform.runLater(() -> handleEvent(event)),
                error -> Platform.runLater(() -> log.warn("SSE error: {}", error.getMessage()))
        );
    }

    private void handleEvent(PaymentEventDto event) {
        if (!event.pin().equals(currentPin)) {
            log.debug("Ignoring event for stale pin");
            return;
        }
        switch (event.type()) {
            case "PAID"      -> notifyPaid(event.jobId());
            case "FAILED",
                 "CANCELLED" -> notifyError("Платёж не прошёл. Попробуйте ещё раз.");
            case "EXPIRED"   -> notifyExpired();
            default          -> log.warn("Unknown event type: {}", event.type());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Countdown
    // ════════════════════════════════════════════════════════════════

    private void startCountdown() {
        secondsLeft = (int) TIMEOUT.getSeconds();
        notifyTick(secondsLeft);

        countdownTimer = new Timeline(new KeyFrame(
                Duration.seconds(TIMER_TICK_SECONDS),
                e -> {
                    secondsLeft -= TIMER_TICK_SECONDS;
                    if (secondsLeft <= 0) {
                        notifyExpired();
                    } else {
                        notifyTick(secondsLeft);
                    }
                }
        ));
        countdownTimer.setCycleCount(Timeline.INDEFINITE);
        countdownTimer.play();
    }

    // ════════════════════════════════════════════════════════════════
    //  Listener
    // ════════════════════════════════════════════════════════════════

    public interface Listener {
        void onLoading();                          // создаём сессию
        void onSessionReady(PaymentSessionDto s);  // QR можно показывать
        void onCountdownTick(int secondsLeft);     // обновить таймер на UI
        void onPaid(UUID jobId);                   // успешная оплата
        void onExpired();                          // вышло время / EXPIRED от Finik
        void onError(String message);              // прочие ошибки
    }

    private void notifyLoading()                       { if (listener != null) listener.onLoading(); }
    private void notifySessionReady(PaymentSessionDto s){ if (listener != null) listener.onSessionReady(s); }
    private void notifyTick(int seconds)               { if (listener != null) listener.onCountdownTick(seconds); }
    private void notifyPaid(UUID jobId)                { if (listener != null) listener.onPaid(jobId); }
    private void notifyExpired()                       { if (listener != null) listener.onExpired(); }
    private void notifyError(String message)           { if (listener != null) listener.onError(message); }
}