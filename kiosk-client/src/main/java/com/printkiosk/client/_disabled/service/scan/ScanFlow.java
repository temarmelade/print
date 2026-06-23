package com.printkiosk.client.service.scan;

import com.printkiosk.model.UploadSource;
import com.printkiosk.ui.state.OperationMode;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanFlow {

    private final ScanService scanService;

    // ----- Session state -----
    private final List<ScanPage> pages = new ArrayList<>();
    private int currentPageIndex = 0;
    private OperationMode currentMode = OperationMode.PRINT;
    private ScanResult currentResult;

    private ScanSessionListener listener;

    // ----- Observable progress (UI bind'ится) -----
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(0);
    private final ReadOnlyStringWrapper progressMessage = new ReadOnlyStringWrapper("");

    // =========================================================================
    //   Lifecycle
    // =========================================================================

    /**
     * Начать новую сессию. Сбрасывает state, прерывает предыдущую если была.
     * Контроллер сам очищает UI (preview image и т. п.) после вызова.
     */
    public void startSession(OperationMode mode, ScanSessionListener listener) {
        this.pages.clear();
        this.currentPageIndex = 0;
        this.currentResult = null;
        this.currentMode = mode;
        this.listener = listener;
        this.progress.set(0);
        this.progressMessage.set("");
    }

    /**
     * Завершить сессию (сбросить state). Используется при возврате на главный
     * экран, inactivity, и т. п.
     */
    public void endSession() {
        this.pages.clear();
        this.currentPageIndex = 0;
        this.currentResult = null;
        this.currentMode = OperationMode.PRINT;
        this.listener = null;
        this.progress.set(0);
        this.progressMessage.set("");
    }

    // =========================================================================
    //   Async scan
    // =========================================================================

    /**
     * Запустить асинхронное сканирование очередной страницы. На успех — добавляет
     * её к {@link #pages}, ставит {@link #currentPageIndex} на новую страницу
     * и зовёт {@link ScanSessionListener#onScanPageCompleted()}. На ошибку —
     * {@link ScanSessionListener#onScanFailed(Throwable)}.
     *
     * <p>Передаваемые сообщения прогресса — на трёх языках. Чтобы вынести их
     * в {@code i18n/messages_*.properties}, нужно прокидывать сюда
     * {@code LocalizationService} — оставлено как есть для минимизации диффа
     * (это можно сделать в отдельной волне).
     *
     * @param messageScanning    статусное сообщение «идёт сканирование»
     * @param messagePreparing   статусное сообщение «подготовка превью»
     */
    public void scanNewPage(String messageScanning, String messagePreparing) {
        if (listener == null) {
            log.warn("scanNewPage called without active session");
            return;
        }

        final int nextPageNumber = pages.size() + 1;
        final ScanSessionListener captured = listener;

        Task<ScanPage> task = new Task<>() {
            @Override
            protected ScanPage call() throws Exception {
                updateMessage(messageScanning);
                updateProgress(0.2, 1.0);
                Thread.sleep(700);

                updateProgress(0.55, 1.0);
                Thread.sleep(700);

                updateMessage(messagePreparing);
                updateProgress(0.85, 1.0);
                Thread.sleep(500);

                return scanService.scanSinglePage(nextPageNumber);
            }
        };

        // Прокидываем прогресс из Task'а в наши observable property
        progress.bind(task.progressProperty());
        progressMessage.bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            progress.unbind();
            progressMessage.unbind();

            ScanPage page = task.getValue();
            pages.add(page);
            currentPageIndex = pages.size() - 1;
            captured.onScanPageCompleted();
        });

        task.setOnFailed(e -> {
            progress.unbind();
            progressMessage.unbind();
            log.error("Scanning failed", task.getException());
            captured.onScanFailed(task.getException());
        });

        Thread worker = new Thread(task, "kiosk-scan-worker");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================
    //   Page management
    // =========================================================================

    /**
     * Удалить текущую страницу. Корректирует {@link #currentPageIndex}: если
     * это была последняя — индекс становится {@code 0}, если ушли за границу —
     * становится {@code pages.size() - 1}.
     */
    public void deleteCurrentPage() {
        if (pages.isEmpty()) return;

        pages.remove(currentPageIndex);

        if (pages.isEmpty()) {
            currentPageIndex = 0;
        } else if (currentPageIndex >= pages.size()) {
            currentPageIndex = pages.size() - 1;
        }
    }

    /** Перейти на следующую страницу. Возвращает true если индекс изменился. */
    public boolean nextPage() {
        if (currentPageIndex < pages.size() - 1) {
            currentPageIndex++;
            return true;
        }
        return false;
    }

    /** Перейти на предыдущую страницу. Возвращает true если индекс изменился. */
    public boolean prevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            return true;
        }
        return false;
    }

    // =========================================================================
    //   Finish — build PDF
    // =========================================================================

    /**
     * Собирает PDF из накопленных страниц синхронно (быстро, без UI-блокировки —
     * меньше 100 мс на 10 страниц). На успех вызывает
     * {@link ScanSessionListener#onScanSessionFinished()} и сохраняет результат
     * в {@link #currentResult}. На ошибку — {@link ScanSessionListener#onScanFailed(Throwable)}.
     *
     * <p>Возвращает {@code false}, если страниц нет (контроллер должен показать
     * сообщение «отсканируйте хотя бы одну»).
     */
    public boolean finishScan() {
        if (pages.isEmpty() || listener == null) {
            return false;
        }

        UploadSource source = currentMode == OperationMode.COPY
                ? UploadSource.COPY
                : UploadSource.SCAN;

        try {
            currentResult = scanService.buildPdfFromPages(pages, source);
            // Listener в FX-thread (мы тут уже из FX, но на всякий случай)
            ScanSessionListener captured = listener;
            Platform.runLater(captured::onScanSessionFinished);
            return true;
        } catch (Exception e) {
            log.error("Failed to build PDF from scanned pages", e);
            ScanSessionListener captured = listener;
            Platform.runLater(() -> captured.onScanFailed(e));
            return true; // вызов произошёл, не считаем что страниц не было
        }
    }

    // =========================================================================
    //   State queries
    // =========================================================================

    public boolean hasPages() {
        return !pages.isEmpty();
    }

    public int getPagesCount() {
        return pages.size();
    }

    public int getCurrentPageIndex() {
        return currentPageIndex;
    }

    public ScanPage getCurrentPage() {
        if (pages.isEmpty()) return null;
        return pages.get(currentPageIndex);
    }

    public OperationMode getCurrentMode() {
        return currentMode;
    }

    public boolean isCopyMode() {
        return currentMode == OperationMode.COPY;
    }

    public ScanResult getCurrentResult() {
        return currentResult;
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty progressMessageProperty() {
        return progressMessage.getReadOnlyProperty();
    }
}
