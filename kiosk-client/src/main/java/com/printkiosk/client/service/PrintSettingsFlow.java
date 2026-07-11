package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.api.ServerUnavailableException;
import com.printkiosk.shared.api.dto.JobPreviewRequest;
import com.printkiosk.shared.api.dto.JobPreviewResponse;
import com.printkiosk.shared.api.dto.PrintSettings;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Управляет состоянием настроек печати и пересчитывает цену через сервер
 * с дебаунсом 300мс — чтобы при быстром кликании +/+/+ не уходил каждый
 * раз HTTP-запрос. Последний клик «выиграет», и через 300мс будет
 * единственный запрос с финальным состоянием.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrintSettingsFlow {

    private static final Duration DEBOUNCE = Duration.millis(300);

    public static final int     MIN_COPIES = 1;
    public static final int     MAX_COPIES = 99;

    public static final String COLOR_BW    = "BW";
    public static final String COLOR_COLOR = "COLOR";
    public static final String ORIENTATION_PORTRAIT  = "PORTRAIT";
    public static final String ORIENTATION_LANDSCAPE = "LANDSCAPE";
    public static final String PAPER_A4    = "A4";

    private final KioskServerClient server;

    // ── Текущее состояние ───────────────────────────────────────
    private int     copies      = 1;
    private String  colorMode   = COLOR_BW;
    private boolean doubleSided = false;
    private String  orientation = ORIENTATION_PORTRAIT;
    private String  paperSize   = PAPER_A4;

    private String   currentPin;
    private java.util.List<Integer> pages;   // выбранные страницы (1-based); null = все
    private Listener listener;
    private Timeline debounceTimer;

    // ════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════

    /** Привязка к экрану настроек без выбора страниц — печатаются все. */
    public void start(String pin) {
        start(pin, null);
    }

    /** Привязка к экрану настроек: сбрасывает к дефолтам, начинает превью. */
    public void start(String pin, java.util.List<Integer> pages) {
        this.currentPin = pin;
        this.pages = pages;
        this.copies = 1;
        this.colorMode = COLOR_BW;
        this.doubleSided = false;
        this.orientation = ORIENTATION_PORTRAIT;
        this.paperSize = PAPER_A4;
        notifyStateChanged();
        schedulePreview();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void stop() {
        cancelDebounce();
        currentPin = null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Settings mutations
    // ════════════════════════════════════════════════════════════════

    public void incrementCopies() {
        if (copies < MAX_COPIES) { copies++; afterChange(); }
    }
    public void decrementCopies() {
        if (copies > MIN_COPIES) { copies--; afterChange(); }
    }
    public void setColorMode(String mode) {
        if (COLOR_BW.equals(mode) || COLOR_COLOR.equals(mode)) {
            this.colorMode = mode; afterChange();
        }
    }
    public void setDoubleSided(boolean value) {
        this.doubleSided = value; afterChange();
    }
    public void setOrientation(String value) {
        if (ORIENTATION_PORTRAIT.equals(value) || ORIENTATION_LANDSCAPE.equals(value)) {
            this.orientation = value; afterChange();
        }
    }
    public void setPaperSize(String value) {
        if (PAPER_A4.equals(value)) {
            this.paperSize = value; afterChange();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Readers — нужны MainController-у для построения UI
    // ════════════════════════════════════════════════════════════════

    public PrintSettings currentSettings() {
        return new PrintSettings(copies, colorMode, doubleSided, orientation, paperSize);
    }
    public int     copies()       { return copies; }
    public String  colorMode()    { return colorMode; }
    public boolean doubleSided()  { return doubleSided; }
    public String  orientation()  { return orientation; }
    public String  paperSize()    { return paperSize; }

    // ════════════════════════════════════════════════════════════════
    //  Internals: debounce + preview
    // ════════════════════════════════════════════════════════════════

    private void afterChange() {
        notifyStateChanged();
        schedulePreview();
    }

    private void schedulePreview() {
        cancelDebounce();
        if (currentPin == null) return;

        debounceTimer = new Timeline(new KeyFrame(DEBOUNCE, e -> fetchPreview()));
        debounceTimer.play();
    }

    private void cancelDebounce() {
        if (debounceTimer != null) {
            debounceTimer.stop();
            debounceTimer = null;
        }
    }

    private void fetchPreview() {
        final String pin = currentPin;
        final PrintSettings settings = currentSettings();
        final java.util.List<Integer> reqPages = pages;
        notifyPriceLoading();

        Task<JobPreviewResponse> task = new Task<>() {
            @Override
            protected JobPreviewResponse call() {
                return server.previewJob(new JobPreviewRequest(pin, settings, reqPages));
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            if (!java.util.Objects.equals(pin, currentPin)) {
                return;  // юзер ушёл с экрана — игнорируем устаревший ответ
            }
            JobPreviewResponse response = task.getValue();
            log.debug("Preview: {} som", response.price().totalSom());
            notifyPriceReady(response);
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            Throwable cause = task.getException();
            if (cause instanceof ServerUnavailableException) {
                log.warn("Preview failed (server unavailable)");
                notifyPriceError("Сервер недоступен");
            } else {
                log.error("Preview failed", cause);
                notifyPriceError("Не удалось рассчитать цену");
            }
        }));

        Thread t = new Thread(task, "preview-pricing");
        t.setDaemon(true);
        t.start();
    }

    // ════════════════════════════════════════════════════════════════
    //  Listener
    // ════════════════════════════════════════════════════════════════

    public interface Listener {
        /** Настройки изменились — обновить подсветку кнопок, лейблы. */
        void onSettingsChanged(PrintSettings settings);
        /** Идёт запрос цены — показать спиннер/затемнение. */
        void onPriceLoading();
        /** Цена получена. */
        void onPriceReady(JobPreviewResponse response);
        /** Цену не удалось получить. */
        void onPriceError(String message);
    }

    private void notifyStateChanged() {
        if (listener != null) listener.onSettingsChanged(currentSettings());
    }
    private void notifyPriceLoading() {
        if (listener != null) listener.onPriceLoading();
    }
    private void notifyPriceReady(JobPreviewResponse r) {
        if (listener != null) listener.onPriceReady(r);
    }
    private void notifyPriceError(String message) {
        if (listener != null) listener.onPriceError(message);
    }
}