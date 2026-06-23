package com.printkiosk.client.printer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.print.Doc;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Универсальная обёртка над {@link DocPrintJob}.
 * <p>
 * Принимает готовый {@link Doc} и атрибуты, отдаёт асинхронный результат
 * через {@link CompletableFuture}. Отслеживает реальный статус печати
 * через {@link javax.print.event.PrintJobListener} — это даёт гарантию,
 * что аппарат действительно завершил задание.
 * <p>
 * Тоnkий момент: не все драйверы шлют {@code printJobCompleted}. Многие
 * Windows-драйверы (Canon в том числе) ограничиваются {@code printJobNoMoreEvents}.
 * Поэтому оба события трактуем как успех.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterDriver {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final PrintExecutor printExecutor;

    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "print-timeout");
        t.setDaemon(true);
        return t;
    });

    public CompletableFuture<PrinterResult> print(PrintService printer,
                                                  Doc doc,
                                                  PrintRequestAttributeSet attributes) {
        return print(printer, doc, attributes, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<PrinterResult> print(PrintService printer,
                                                  Doc doc,
                                                  PrintRequestAttributeSet attributes,
                                                  Duration timeout) {
        CompletableFuture<PrinterResult> result = new CompletableFuture<>();

        printExecutor.executor().submit(() -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<PrinterResult> outcome = new AtomicReference<>();

            DocPrintJob job = printer.createPrintJob();
            job.addPrintJobListener(new PrintJobAdapter() {
                @Override public void printJobCompleted(PrintJobEvent e) {
                    log.debug("Listener: COMPLETED");
                    outcome.compareAndSet(null, PrinterResult.completed());
                    latch.countDown();
                }
                @Override public void printJobNoMoreEvents(PrintJobEvent e) {
                    log.debug("Listener: NO_MORE_EVENTS");
                    outcome.compareAndSet(null, PrinterResult.completed());
                    latch.countDown();
                }
                @Override public void printJobFailed(PrintJobEvent e) {
                    log.warn("Listener: FAILED");
                    outcome.set(PrinterResult.failed("Принтер сообщил об ошибке"));
                    latch.countDown();
                }
                @Override public void printJobCanceled(PrintJobEvent e) {
                    log.warn("Listener: CANCELED");
                    outcome.set(PrinterResult.failed("Задание отменено"));
                    latch.countDown();
                }
                @Override public void printDataTransferCompleted(PrintJobEvent e) {
                    log.debug("Listener: DATA_TRANSFERRED");
                }
            });

            // Сторожевой таймаут — на случай если listener вообще не сработает.
            ScheduledFuture<?> watchdog = timeoutScheduler.schedule(() -> {
                if (latch.getCount() > 0) {
                    log.warn("Print job watchdog fired after {}", timeout);
                    outcome.compareAndSet(null,
                            PrinterResult.failed("Принтер не отвечает (timeout)"));
                    latch.countDown();
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            try {
                job.print(doc, attributes);

                if (!latch.await(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS)) {
                    result.completeExceptionally(new PrinterTimeoutException(
                            "Принтер не ответил за " + timeout.toSeconds() + " секунд"));
                    return;
                }

                PrinterResult r = outcome.get();
                result.complete(r != null ? r
                        : PrinterResult.failed("Принтер не сообщил результат"));

            } catch (PrintException e) {
                log.error("Print job rejected by driver", e);
                result.complete(PrinterResult.failed(
                        "Драйвер отклонил задание: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(e);
            } catch (Throwable t) {
                log.error("Unexpected printer error", t);
                result.completeExceptionally(t);
            } finally {
                watchdog.cancel(false);
            }
        });

        return result;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        timeoutScheduler.shutdownNow();
    }
}