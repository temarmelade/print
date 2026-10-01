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
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;
import org.springframework.stereotype.Service;

import javax.print.PrintService;
import javax.print.attribute.PrintRequestAttributeSet;
import java.awt.print.Book;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterAbortException;
import java.awt.print.PrinterJob;
import java.util.concurrent.CompletableFuture;

/**
 * Fallback-путь: растеризуем PDF через PDFBox + {@link PrinterJob}.
 * <p>
 * На Windows это путь для ВСЕХ PDF: драйверы Windows не принимают
 * {@code DocFlavor.BYTE_ARRAY.PDF} (его понимает только CUPS на Linux/macOS),
 * поэтому документы, сканы и ксерокопии печатаются здесь.
 * <p>
 * Как понимаем, что задание принято: {@link PrinterJob#print} синхронный и
 * возвращается, когда задание целиком передано в очередь Windows; ошибка
 * драйвера приходит исключением. Раньше здесь ждали события PrintJobListener,
 * повешенного на отдельный DocPrintJob, которым ничего не печаталось, — событие
 * не приходило никогда, и каждый PDF висел на экране «Печать» до 2-минутного
 * таймаута, после чего напечатанный заказ помечался неудачным.
 * <p>
 * Когда листы физически выйдут, отслеживает {@link PrintCompletionWatcher}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackPdfPrinter {

    /** Имя задания в очереди Windows — видно в «Устройствах и принтерах». */
    private static final String JOB_NAME = "PrintKiosk";

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
                job.setJobName(JOB_NAME);
                job.setPageable(a4Pages(doc));

                PrintRequestAttributeSet attrs = attributesBuilder.build(settings, printer);
                long started = System.nanoTime();

                job.print(attrs);   // блокирует, пока Windows не примет задание целиком

                log.info("Задание принято очередью печати за {} мс",
                        (System.nanoTime() - started) / 1_000_000);
                result.complete(PrinterResult.completed());

            } catch (PrinterAbortException e) {
                log.warn("Fallback print aborted: {}", e.getMessage());
                result.complete(PrinterResult.failed("Задание отменено"));
            } catch (Throwable t) {
                log.error("Fallback print failed", t);
                result.complete(PrinterResult.failed(
                        "Ошибка печати: " + t.getMessage()));
            }
        });

        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  Формат бумаги: всегда A4
    // ════════════════════════════════════════════════════════════════

    private static final double A4_W = PDRectangle.A4.getWidth();    // 595 pt = 210 мм
    private static final double A4_H = PDRectangle.A4.getHeight();   // 842 pt = 297 мм
    /** Насколько размер страницы может отличаться от A4, чтобы считаться A4 (≈1 мм). */
    private static final double A4_TOLERANCE = 3;

    /**
     * Каждая страница — на листе A4, в той ориентации, в какой свёрстана.
     *
     * <p>Раньше здесь был PDFPageable: он отдаёт драйверу формат каждой
     * страницы таким, каким он записан в файле. Документ, свёрстанный под
     * Letter (по умолчанию в Word с американскими настройками, многие PDF из
     * интернета; LibreOffice при конвертации DOCX формат сохраняет), уходил на
     * принтер как Letter. Canon с лотком A4 вставал с «Size/Settings Mismatch,
     * PC Set.: LTR» и ждал, пока кто-нибудь нажмёт кнопку на самом принтере.
     *
     * <p>Теперь бумага всегда A4. Страница A4 печатается в натуральную
     * величину, как и раньше; другой формат вписывается в лист с уменьшением
     * (SHRINK_TO_FIT) — ничего не обрезается.
     */
    static Book a4Pages(PDDocument doc) {
        PDFPrintable actualSize  = new PDFPrintable(doc, Scaling.ACTUAL_SIZE,   false, 0, true);
        PDFPrintable shrinkToFit = new PDFPrintable(doc, Scaling.SHRINK_TO_FIT, false, 0, true);

        Paper a4 = new Paper();
        a4.setSize(A4_W, A4_H);
        // Вся площадь листа; реальные поля принтера Java подставит сама.
        a4.setImageableArea(0, 0, A4_W, A4_H);

        Book book = new Book();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            PageFormat format = new PageFormat();
            format.setPaper(a4);
            format.setOrientation(isLandscape(page) ? PageFormat.LANDSCAPE : PageFormat.PORTRAIT);
            // Book передаёт печатающему объекту свой номер страницы, а он
            // совпадает с номером страницы в PDF — страницы идут по порядку.
            book.append(isA4(page) ? actualSize : shrinkToFit, format);
        }
        return book;
    }

    /** Альбомная ли страница на вид — с учётом поворота /Rotate в PDF. */
    static boolean isLandscape(PDPage page) {
        PDRectangle box = page.getCropBox();
        boolean wider   = box.getWidth() > box.getHeight();
        boolean rotated = (page.getRotation() / 90) % 2 != 0;   // 90 или 270 (в т.ч. -90)
        return wider != rotated;
    }

    /** Страница формата A4 — в любой ориентации. */
    static boolean isA4(PDPage page) {
        PDRectangle box = page.getCropBox();
        double w = box.getWidth(), h = box.getHeight();
        return (Math.abs(w - A4_W) <= A4_TOLERANCE && Math.abs(h - A4_H) <= A4_TOLERANCE)
            || (Math.abs(w - A4_H) <= A4_TOLERANCE && Math.abs(h - A4_W) <= A4_TOLERANCE);
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