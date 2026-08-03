package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.api.PaymentStreamClient;
import com.printkiosk.client.service.scan.ScanFlow;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.PaymentEventDto;
import com.printkiosk.shared.api.dto.PaymentSessionDto;
import com.printkiosk.shared.api.dto.UploadResponse;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Closeable;

/**
 * Оплата цифровой доставки отсканированного документа (получение через
 * сайт или Telegram). По образцу {@link PaymentSessionFlow}, но со своей
 * тарификацией и жизненным циклом:
 * <ol>
 *   <li>собирает PDF из сканов и заливает его на сервер (source=SCAN);</li>
 *   <li>открывает оплату доставки (фиксированная плата за страницу) —
 *       сервер отдаёт платёжный QR-URL и сумму;</li>
 *   <li>подписывается на общий SSE-поток оплаты по PIN и ждёт webhook PAID.</li>
 * </ol>
 * UI сам JavaFX-узлы здесь не трогает — только через {@link Listener}.
 * <p>
 * Печать сканов через этот флоу не идёт: она бесплатна и обрабатывается
 * обычным трактом печати.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanDeliveryFlow {

    /** Столько живёт платёжная сессия (согласовано с серверным таймаутом Finik). */
    private static final java.time.Duration TIMEOUT = java.time.Duration.ofMinutes(5);

    private final KioskServerClient   server;
    private final PaymentStreamClient streamClient;
    private final ScanFlow            scanFlow;

    private Listener listener;

    // Состояние текущей сессии доставки.
    private String  pin;
    private String  paymentUrl;
    private int     priceSom;
    private boolean paid;
    /** Идёт подготовка оплаты — защищает от повторного запуска при двойном тапе. */
    private boolean preparing;
    /** Последний выбранный канал («WEB»/«TELEGRAM») — фиксируется в БД при оплате. */
    private String  channel;

    private Closeable sseSubscription;
    private Timeline  timeout;

    public void setListener(Listener listener) { this.listener = listener; }

    /** PIN загруженного скана — по нему UI строит ссылку получения после оплаты. */
    public String pin() { return pin; }

    // ════════════════════════════════════════════════════════════════
    //  Public
    // ════════════════════════════════════════════════════════════════

    /**
     * Готовит оплату доставки и показывает платёжный QR. {@code channel} —
     * «WEB» или «TELEGRAM»: определяет тип операции, который сервер запишет
     * в историю. Идемпотентно в рамках одной сессии доставки: если оплата
     * уже подготовлена (например, пользователь переключился между «веб» и
     * «Telegram» до оплаты), новый платёж не создаётся — переиспользуется
     * существующий QR (сумма и PIN те же; тип операции остаётся тем, что был
     * при первом создании).
     */
    public void preparePayment(String channel) {
        // Запоминаем последний выбор всегда — даже если QR уже готов и мы
        // короткозамыкаемся ниже: при оплате зафиксируем именно этот канал.
        this.channel = channel;
        if (paid) {                         // уже оплачено — ничего не пересоздаём
            notifyPaid();
            return;
        }
        if (paymentUrl != null) {           // QR уже готов — просто перерисовать
            notifyPaymentReady(paymentUrl, priceSom);
            return;
        }
        if (preparing) {                    // подготовка уже идёт — игнорируем повтор
            return;
        }
        if (!scanFlow.hasPages()) {
            log.warn("Scan delivery payment requested with no scanned pages");
            notifyError("scanupload.delivery.failed");
            return;
        }

        preparing = true;
        notifyPreparing();

        Task<PaymentSessionDto> task = new Task<>() {
            @Override protected PaymentSessionDto call() throws Exception {
                java.io.File pdf = scanFlow.buildPdf();
                UploadResponse uploaded = server.uploadFile(pdf, UploadSource.SCAN);
                pin = uploaded.pin();
                return server.createScanDeliveryPayment(pin, channel);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            preparing = false;
            PaymentSessionDto session = task.getValue();
            paymentUrl = session.paymentUrl();
            priceSom   = session.priceSom();
            log.info("Scan delivery payment ready: priceSom={}", priceSom);
            notifyPaymentReady(paymentUrl, priceSom);
            subscribe(pin);
            startTimeout();
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            preparing = false;
            log.error("Scan delivery payment preparation failed", task.getException());
            resetState();
            notifyError("scanupload.delivery.failed");
        }));

        Thread t = new Thread(task, "scan-delivery-pay");
        t.setDaemon(true);
        t.start();
    }

    /** Полный сброс сессии доставки (отписка SSE, остановка таймера, очистка стейта). */
    public void stop() {
        resetState();
    }

    // ════════════════════════════════════════════════════════════════
    //  SSE
    // ════════════════════════════════════════════════════════════════

    private void subscribe(String pin) {
        sseSubscription = streamClient.connect(
                pin,
                event -> Platform.runLater(() -> handleEvent(event)),
                error -> Platform.runLater(() -> log.warn("SSE error: {}", error.getMessage())));
    }

    private void handleEvent(PaymentEventDto event) {
        if (pin == null || !pin.equals(event.pin())) {
            return;   // событие не про нашу сессию
        }
        switch (event.type()) {
            case "PAID" -> {
                paid = true;
                closeSse();
                stopTimeout();          // pin сохраняем — по нему строим ссылку получения
                finalizeChannelAsync(); // фиксируем фактический канал в БД (в фоне)
                notifyPaid();
            }
            case "FAILED", "CANCELLED" -> {
                resetState();
                notifyError("scan.delivery.pay.failed");
            }
            case "EXPIRED" -> {
                resetState();
                notifyExpired();
            }
            default -> log.warn("Unknown scan delivery event type: {}", event.type());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Timeout
    // ════════════════════════════════════════════════════════════════

    private void startTimeout() {
        stopTimeout();
        timeout = new Timeline(new KeyFrame(Duration.seconds(TIMEOUT.getSeconds()), e -> {
            resetState();
            notifyExpired();
        }));
        timeout.setCycleCount(1);
        timeout.play();
    }

    // ════════════════════════════════════════════════════════════════
    //  Internal
    // ════════════════════════════════════════════════════════════════

    /**
     * Фиксирует фактический канал в БД после оплаты. Сетевой вызов — уводим
     * с FX-потока в фон; UI при этом уже показывает QR получения. Ошибку лишь
     * логируем: документ пользователь получит в любом случае, а тип операции
     * в худшем случае останется тем, что был при создании сессии.
     */
    private void finalizeChannelAsync() {
        final String p = pin;
        final String ch = channel;
        if (p == null || ch == null) return;
        Thread t = new Thread(() -> {
            try {
                server.finalizeScanDeliveryChannel(p, ch);
            } catch (Exception e) {
                log.warn("Failed to finalize scan delivery channel: {}", e.getMessage());
            }
        }, "scan-delivery-finalize");
        t.setDaemon(true);
        t.start();
    }

    private void closeSse() {
        if (sseSubscription != null) {
            try { sseSubscription.close(); }
            catch (Exception e) { log.debug("SSE close error: {}", e.getMessage()); }
            sseSubscription = null;
        }
    }

    private void stopTimeout() {
        if (timeout != null) { timeout.stop(); timeout = null; }
    }

    private void resetState() {
        closeSse();
        stopTimeout();
        pin        = null;
        paymentUrl = null;
        priceSom   = 0;
        paid       = false;
        preparing  = false;
        channel    = null;
    }

    // ── listener bridge ────────────────────────────────────────────
    private void notifyPreparing() { if (listener != null) listener.onPreparing(); }
    private void notifyPaymentReady(String url, int som) {
        if (listener != null) listener.onPaymentReady(url, som);
    }
    private void notifyPaid()      { if (listener != null) listener.onPaid(); }
    private void notifyExpired()   { if (listener != null) listener.onExpired(); }
    private void notifyError(String key) { if (listener != null) listener.onError(key); }

    /** UI-события оплаты цифровой доставки скана. */
    public interface Listener {
        /** Готовим документ и платёжную сессию — можно показать индикатор. */
        void onPreparing();
        /** Платёжный QR готов: {@code paymentUrl} и сумма к оплате. */
        void onPaymentReady(String paymentUrl, int priceSom);
        /** Оплата подтверждена (webhook PAID) — пора показать QR получения. */
        void onPaid();
        /** Вышло время оплаты. */
        void onExpired();
        /** Ошибка (ключ локализации). */
        void onError(String messageKey);
    }
}
