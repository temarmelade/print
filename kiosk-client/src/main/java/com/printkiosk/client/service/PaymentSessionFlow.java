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
 * Управляет всем циклом оплаты: createJob → createPayment → ожидание
 * подтверждения → уведомление UI.
 *
 * <h2>Два канала подтверждения</h2>
 * <b>SSE</b> — быстрый: событие приходит в тот же момент, когда сервер
 * получил webhook от банка.
 *
 * <p><b>Опрос статуса</b> — надёжный. Идёт постоянно и независимо от
 * SSE. Источником истины является сервер: он знает об оплате из webhook
 * банка, и это состояние никуда не денется, даже если поток оборвался.
 *
 * <p>Почему нельзя полагаться на один SSE: соединение живёт минутами и
 * почти всё это время молчит — человек в телефоне. Любой прокси считает
 * такое соединение мёртвым и закрывает (у Nginx порог 60 секунд между
 * чтениями, у Cloudflare 125). Heartbeat это смягчает, но не отменяет
 * обрывов сети, перезапуска сервера и потерянных пакетов. Оборванный
 * поток не должен означать потерянный платёж.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSessionFlow {

    private static final java.time.Duration TIMEOUT     = java.time.Duration.ofMinutes(5);
    private static final int TIMER_TICK_SECONDS         = 1;

    /**
     * Период опроса статуса. 3 секунды — незаметная для человека
     * задержка и незначительная нагрузка: запросов идёт по одному на
     * киоск, и только пока открыт экран оплаты.
     */
    private static final int STATUS_POLL_SECONDS        = 3;

    private final KioskServerClient    server;
    private final PaymentStreamClient  streamClient;

    private Listener listener;
    private String   currentPin;
    private UUID     currentJobId;
    private String   currentPaymentId;

    private Closeable sseSubscription;
    private Timeline  countdownTimer;
    private Timeline  statusPoller;
    private int       secondsLeft;

    /**
     * Оплата уже подтверждена. Нужен, потому что подтверждение может
     * прийти двумя путями почти одновременно, а перейти к печати нужно
     * ровно один раз.
     */
    private boolean   settled;

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
            settled = false;
            notifySessionReady(session);
            subscribeToEvents(pin);
            startStatusPolling(pin);
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
        if (statusPoller != null) {
            statusPoller.stop();
            statusPoller = null;
        }
        settled = false;
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
            log.debug("Событие по чужому PIN — игнорируем");
            return;
        }
        switch (event.type()) {
            case "PAID"      -> confirmPaid(event.jobId(), "SSE");
            case "FAILED",
                 "CANCELLED" -> notifyError("Платёж не прошёл. Попробуйте ещё раз.");
            case "EXPIRED"   -> notifyExpired();
            default          -> log.warn("Неизвестный тип события: {}", event.type());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Опрос статуса — надёжный канал
    // ════════════════════════════════════════════════════════════════

    /**
     * Спрашивает сервер о статусе, пока открыт экран оплаты.
     *
     * <p>Работает всегда, а не только при обрыве SSE: определить обрыв
     * надёжно нельзя — соединение может висеть «живым», не доставляя
     * событий. Постоянный опрос стоит один запрос в 3 секунды на киоск
     * и снимает целый класс проблем.
     */
    private void startStatusPolling(String pin) {
        statusPoller = new Timeline(new KeyFrame(
                Duration.seconds(STATUS_POLL_SECONDS),
                e -> checkStatus(pin)));
        statusPoller.setCycleCount(Timeline.INDEFINITE);
        statusPoller.play();
    }

    private void checkStatus(String pin) {
        if (settled || !pin.equals(currentPin)) return;

        Task<PaymentStatusDto> task = new Task<>() {
            @Override protected PaymentStatusDto call() {
                return server.paymentStatus(pin);
            }
        };

        task.setOnSucceeded(e -> {
            PaymentStatusDto status = task.getValue();
            if (status != null && status.isPaid()) {
                Platform.runLater(() -> confirmPaid(status.jobId(), "опрос"));
            }
        });

        // Ошибку опроса не показываем человеку: сеть могла моргнуть, а
        // следующая попытка через 3 секунды. Экран оплаты при этом
        // продолжает работать.
        task.setOnFailed(e ->
                log.debug("Опрос статуса не удался: {}", task.getException().getMessage()));

        Thread t = new Thread(task, "payment-status-poll");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Единая точка подтверждения. Оба канала ведут сюда, но перейти к
     * печати нужно один раз — иначе задание ушло бы на принтер дважды.
     */
    private void confirmPaid(UUID jobId, String source) {
        if (settled) {
            log.debug("Повторное подтверждение ({}) — уже обработано", source);
            return;
        }
        settled = true;
        log.info("Оплата подтверждена, канал: {}", source);

        if (statusPoller != null) statusPoller.stop();
        notifyPaid(jobId);
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