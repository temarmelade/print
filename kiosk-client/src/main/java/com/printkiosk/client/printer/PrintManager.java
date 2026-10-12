package com.printkiosk.client.printer;

import com.printkiosk.client.config.KioskClientProperties;
import com.printkiosk.shared.api.dto.PrintSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.print.DocFlavor;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Высокоуровневый API печати: принимает путь к файлу + MIME + настройки,
 * автоматически выбирает быстрый или fallback-путь в зависимости от того,
 * что умеет драйвер.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrintManager {

    private final KioskClientProperties properties;
    private final FastPathPrinter     fastPath;
    private final FallbackPdfPrinter  fallback;
    private final PrintCompletionWatcher completionWatcher;

    public CompletableFuture<PrinterResult> printAsync(Path file,
                                                       String mimeType,
                                                       PrintSettings settings) {
        return printAsync(file, mimeType, settings, null);
    }

    /**
     * Печать с учётом выбранных страниц. Если {@code pages} задаёт непустое
     * подмножество PDF — файл предварительно фильтруется до этих страниц (в
     * порядке возрастания), после чего печатается целиком. Так и быстрый, и
     * fallback-путь, и атрибуты остаются нетронутыми — что важно для дешёвых
     * драйверов, которые могут игнорировать атрибут page-ranges.
     *
     * @param pages выбранные страницы (1-based); null/пусто/все → печать без фильтра
     */
    public CompletableFuture<PrinterResult> printAsync(Path file,
                                                       String mimeType,
                                                       PrintSettings settings,
                                                       java.util.List<Integer> pages) {
        return printAsync(file, mimeType, settings, pages, java.util.Map.of());
    }

    /**
     * @param rotations поворот страниц, заданный кнопкой на превью: номер
     *                  страницы (с 0, в исходном документе) → градусы по часовой
     */
    public CompletableFuture<PrinterResult> printAsync(Path file,
                                                       String mimeType,
                                                       PrintSettings settings,
                                                       java.util.List<Integer> pages,
                                                       java.util.Map<Integer, Integer> rotations) {
        PrintService printer = resolvePrinter();
        if (printer == null) {
            return CompletableFuture.completedFuture(PrinterResult.failed(
                    "Принтер «" + properties.getPrinter().getName() + "» не найден в Windows"));
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    PrinterResult.failed("Не удалось прочитать файл: " + e.getMessage()));
        }

        if (mimeType == null) {
            return CompletableFuture.completedFuture(
                    PrinterResult.failed("MIME-тип не определён"));
        }

        // Только для PDF имеет смысл выбор страниц; изображения одностраничны.
        if ("application/pdf".equalsIgnoreCase(mimeType)) {
            try {
                // Сначала поворот, потом отбор: номера в rotations — страницы
                // исходного документа, как на превью.
                bytes = rotatePdfPages(bytes, rotations);
                bytes = filterPdfPages(bytes, pages);
            } catch (IOException e) {
                return CompletableFuture.completedFuture(
                        PrinterResult.failed("Не удалось подготовить страницы: " + e.getMessage()));
            }
        }

        // Фото: поворачиваем сами пиксели и печатаем как PNG.
        int imageRotation = (rotations == null) ? 0 : Math.floorMod(rotations.getOrDefault(0, 0), 360);
        if (imageRotation != 0 && mimeType.toLowerCase().startsWith("image/")) {
            try {
                bytes = rotateImage(bytes, imageRotation);
                mimeType = "image/png";
            } catch (IOException e) {
                return CompletableFuture.completedFuture(
                        PrinterResult.failed("Не удалось повернуть изображение: " + e.getMessage()));
            }
        }

        // Счётчик страниц принтера ДО отправки: по его приросту поймём,
        // что листы действительно вышли (см. PrintCompletionWatcher).
        Integer counterBefore = completionWatcher.snapshot();
        int expectedPages = expectedPages(bytes, mimeType, settings);

        CompletableFuture<PrinterResult> printing = switch (mimeType.toLowerCase()) {
            case "application/pdf"  -> printPdf(printer, bytes, settings);
            case "image/jpeg"       -> printImage(printer, bytes, DocFlavor.BYTE_ARRAY.JPEG, settings);
            case "image/png"        -> printImage(printer, bytes, DocFlavor.BYTE_ARRAY.PNG, settings);
            default -> CompletableFuture.completedFuture(
                    PrinterResult.failed("Неподдерживаемый тип файла: " + mimeType));
        };

        // Оба пути сообщают об успехе, когда Windows принял задание в очередь,
        // а бумага выходит позже. Экран «Печать» держим до выхода листов.
        return printing.thenCompose(r -> r.success()
                ? completionWatcher.awaitPrinted(counterBefore, expectedPages).thenApply(v -> r)
                : CompletableFuture.completedFuture(r));
    }

    /**
     * Сколько страниц должен выдать принтер: страницы документа × копии.
     * 0 — не удалось посчитать, тогда выхода листов не ждём.
     */
    private static int expectedPages(byte[] bytes, String mimeType, PrintSettings settings) {
        int pages = 1;   // изображение — одна страница
        if ("application/pdf".equalsIgnoreCase(mimeType)) {
            try (org.apache.pdfbox.pdmodel.PDDocument doc =
                         org.apache.pdfbox.Loader.loadPDF(bytes)) {
                pages = doc.getNumberOfPages();
            } catch (IOException e) {
                return 0;
            }
        }
        int copies = (settings != null) ? Math.max(1, settings.copies()) : 1;
        return pages * copies;
    }

    /**
     * Возвращает PDF, содержащий только выбранные страницы (1-based, по
     * возрастанию, без дублей). Если выбор пуст/некорректен или охватывает
     * все страницы — возвращает исходные байты без изменений.
     */
    /**
     * Поворот страниц PDF через стандартный атрибут /Rotate: к уже записанному
     * в файле повороту добавляется выбранный на превью. Дальше печать на A4
     * учитывает /Rotate сама (FallbackPdfPrinter.a4Pages): повёрнутая
     * страница печатается в своей новой ориентации.
     */
    static byte[] rotatePdfPages(byte[] pdfBytes, java.util.Map<Integer, Integer> rotations) throws IOException {
        if (rotations == null || rotations.values().stream().allMatch(d -> Math.floorMod(d, 360) == 0)) {
            return pdfBytes;
        }
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            int total = doc.getNumberOfPages();
            for (var e : rotations.entrySet()) {
                int index = e.getKey();
                int degrees = Math.floorMod(e.getValue(), 360);
                if (index < 0 || index >= total || degrees == 0) continue;
                org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(index);
                // Ставим на саму страницу (а не наследуем): так поворот
                // переживёт и отбор страниц (importPage в filterPdfPages).
                page.setRotation(Math.floorMod(page.getRotation() + degrees, 360));
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            log.info("Повёрнуты страницы для печати: {}", rotations);
            return out.toByteArray();
        }
    }

    /** Поворот фото по часовой на 90/180/270 — пиксели, на белом фоне (у PNG бывает прозрачность). */
    static byte[] rotateImage(byte[] imageBytes, int degrees) throws IOException {
        java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        if (src == null) throw new IOException("формат изображения не распознан");
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = degrees == 90 || degrees == 270;
        java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(
                swap ? h : w, swap ? w : h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
        switch (degrees) {
            case 90  -> { at.translate(h, 0); at.rotate(Math.PI / 2); }
            case 180 -> { at.translate(w, h); at.rotate(Math.PI); }
            case 270 -> { at.translate(0, w); at.rotate(-Math.PI / 2); }
            default  -> { return imageBytes; }
        }
        java.awt.Graphics2D g = dst.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
            g.drawImage(src, at, null);
        } finally {
            g.dispose();
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(dst, "png", out);
        return out.toByteArray();
    }

    private byte[] filterPdfPages(byte[] pdfBytes, java.util.List<Integer> pages) throws IOException {
        if (pages == null || pages.isEmpty()) return pdfBytes;

        try (org.apache.pdfbox.pdmodel.PDDocument src =
                     org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            int total = src.getNumberOfPages();

            java.util.List<Integer> wanted = pages.stream()
                    .filter(p -> p != null && p >= 1 && p <= total)
                    .distinct()
                    .sorted()
                    .toList();

            // ничего валидного или выбраны все страницы — печатаем как есть
            if (wanted.isEmpty() || wanted.size() == total) return pdfBytes;

            try (org.apache.pdfbox.pdmodel.PDDocument dst =
                         new org.apache.pdfbox.pdmodel.PDDocument()) {
                for (int p : wanted) {
                    dst.importPage(src.getPage(p - 1));
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                dst.save(out);
                log.info("PDF отфильтрован до {} из {} страниц для печати", wanted.size(), total);
                return out.toByteArray();
            }
        }
    }

    // ── Routing ────────────────────────────────────────────────────

    private CompletableFuture<PrinterResult> printPdf(PrintService printer,
                                                      byte[] bytes,
                                                      PrintSettings settings) {
        if (printer.isDocFlavorSupported(DocFlavor.BYTE_ARRAY.PDF)) {
            log.debug("Printer '{}' supports native PDF — using fast path", printer.getName());
            return fastPath.print(printer, bytes, DocFlavor.BYTE_ARRAY.PDF, settings);
        }
        log.info("Printer '{}' does NOT support native PDF — falling back to rasterization",
                printer.getName());
        return fallback.printPdf(printer, bytes, settings);
    }

    private CompletableFuture<PrinterResult> printImage(PrintService printer,
                                                        byte[] bytes,
                                                        DocFlavor flavor,
                                                        PrintSettings settings) {
        if (printer.isDocFlavorSupported(flavor)) {
            log.debug("Printer '{}' supports {} — using fast path", printer.getName(), flavor.getMimeType());
            return fastPath.print(printer, bytes, flavor, settings);
        }
        log.info("Printer '{}' does NOT support {} — falling back to PDF wrapping",
                printer.getName(), flavor.getMimeType());
        return fallback.printImage(printer, bytes, settings);
    }

    // ── Printer resolution ─────────────────────────────────────────

    /**
     * Только принтер из kiosk.printer.name. Раньше при ненайденном имени
     * задание уходило в принтер по умолчанию — «Microsoft Print to PDF» —
     * и вместо печати открывалось окно сохранения PDF. См. PrinterLocator.
     */
    private PrintService resolvePrinter() {
        String configuredName = properties.getPrinter().getName();
        PrintService printer = PrinterLocator.find(configuredName);
        if (printer == null) {
            log.error("Принтер «{}» не найден в Windows — печать отменена. Доступны: {}",
                    configuredName, PrinterLocator.availableNames());
        } else if (PrinterLocator.isVirtual(printer)) {
            log.warn("Печать идёт на виртуальный принтер «{}» — так задано в kiosk.printer.name",
                    printer.getName());
        }
        return printer;
    }
}