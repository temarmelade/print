package com.printkiosk.client.printer;

import com.printkiosk.shared.api.dto.PrintSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.printing.PDFPageable;
import org.springframework.stereotype.Service;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fallback-путь: растеризуем PDF через PDFBox + PrinterJob, и отслеживаем
 * результат через PrintJobListener на лежащем под капотом DocPrintJob.
 * <p>
 * Используется когда принтер не поддерживает {@code DocFlavor.BYTE_ARRAY.PDF}.
 * Нагружает CPU на стороне киоска, но это единственный путь для дешёвых
 * USB-принтеров типа Canon LBP6030.
 * <p>
 * Технически мы не используем {@link PrinterDriver}, потому что
 * {@link PrinterJob#print()} имеет другой API — но логика та же:
 * вешаем listener, ждём с таймаутом, отдаём future.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackPdfPrinter {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final PrintExecutor printExecutor;
    private final PrintAttributesBuilder attributesBuilder;

    public CompletableFuture<PrinterResult> printPdf(PrintService printer,
                                                     byte[] pdfBytes,
                                                     PrintSettings settings) {
        return runAsync(printer, settings, () -> Loader.loadPDF(pdfBytes));
    }

    public CompletableFuture<PrinterResult> printImage(PrintService printer,
                                                       byte[] imageBytes,
                                                       PrintSettings settings) {
        return runAsync(printer, settings, () -> wrapImageInPdf(imageBytes, settings));
    }

    private CompletableFuture<PrinterResult> runAsync(PrintService printer,
                                                      PrintSettings settings,
                                                      PdfSupplier pdfSupplier) {
        CompletableFuture<PrinterResult> result = new CompletableFuture<>();

        printExecutor.executor().submit(() -> {
            try (PDDocument doc = pdfSupplier.get()) {
                log.info("Fallback path: rasterizing {} pages on CPU for '{}'",
                        doc.getNumberOfPages(), printer.getName());

                PrinterJob job = PrinterJob.getPrinterJob();
                job.setPrintService(printer);
                job.setPageable(new PDFPageable(doc));

                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<PrinterResult> outcome = new AtomicReference<>();

                // PrinterJob позволяет получить DocPrintJob через PrintService
                // и слушать события через ту же связку listener'ов.
                DocPrintJob underlying = printer.createPrintJob();
                underlying.addPrintJobListener(makeListener(outcome, latch));

                // Watchdog
                ScheduledFuture<?> watchdog = scheduleWatchdog(latch, outcome);

                try {
                    PrintRequestAttributeSet attrs = attributesBuilder.build(settings, printer);
                    job.print(attrs);

                    if (!latch.await(TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                        result.completeExceptionally(
                                new PrinterTimeoutException("Принтер не ответил вовремя"));
                        return;
                    }

                    PrinterResult r = outcome.get();
                    result.complete(r != null ? r
                            : PrinterResult.failed("Нет ответа от принтера"));

                } finally {
                    watchdog.cancel(false);
                }

            } catch (Throwable t) {
                log.error("Fallback print failed", t);
                result.complete(PrinterResult.failed(
                        "Ошибка печати: " + t.getMessage()));
            }
        });

        return result;
    }

    private PrintJobAdapter makeListener(AtomicReference<PrinterResult> outcome, CountDownLatch latch) {
        return new PrintJobAdapter() {
            @Override public void printJobCompleted(PrintJobEvent e) {
                outcome.compareAndSet(null, PrinterResult.completed());
                latch.countDown();
            }
            @Override public void printJobNoMoreEvents(PrintJobEvent e) {
                outcome.compareAndSet(null, PrinterResult.completed());
                latch.countDown();
            }
            @Override public void printJobFailed(PrintJobEvent e) {
                outcome.set(PrinterResult.failed("Принтер сообщил об ошибке"));
                latch.countDown();
            }
            @Override public void printJobCanceled(PrintJobEvent e) {
                outcome.set(PrinterResult.failed("Задание отменено"));
                latch.countDown();
            }
        };
    }

    private ScheduledFuture<?> scheduleWatchdog(CountDownLatch latch,
                                                AtomicReference<PrinterResult> outcome) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fallback-print-watchdog");
            t.setDaemon(true);
            return t;
        }).schedule(() -> {
            if (latch.getCount() > 0) {
                outcome.compareAndSet(null,
                        PrinterResult.failed("Принтер не отвечает (timeout)"));
                latch.countDown();
            }
        }, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private PDDocument wrapImageInPdf(byte[] imageBytes, PrintSettings settings) throws Exception {
        PDDocument document = new PDDocument();
        try {
            boolean landscape = "LANDSCAPE".equalsIgnoreCase(settings.orientation());
            PDRectangle pageSize = landscape
                    ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                    : PDRectangle.A4;

            PDPage page = new PDPage(pageSize);
            document.addPage(page);

            PDImageXObject image = PDImageXObject.createFromByteArray(document, imageBytes, "image");

            float pw = page.getMediaBox().getWidth();
            float ph = page.getMediaBox().getHeight();
            float m = 24f;
            float scale = Math.min((pw - m * 2) / image.getWidth(),
                    (ph - m * 2) / image.getHeight());
            float dw = image.getWidth() * scale;
            float dh = image.getHeight() * scale;
            float x = (pw - dw) / 2;
            float y = (ph - dh) / 2;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.drawImage(image, x, y, dw, dh);
            }
            return document;
        } catch (Throwable t) {
            document.close();
            throw t;
        }
    }

    @FunctionalInterface
    private interface PdfSupplier {
        PDDocument get() throws Exception;
    }
}