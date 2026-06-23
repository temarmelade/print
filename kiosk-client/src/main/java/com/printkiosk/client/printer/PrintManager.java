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

    public CompletableFuture<PrinterResult> printAsync(Path file,
                                                       String mimeType,
                                                       PrintSettings settings) {
        PrintService printer = resolvePrinter();
        if (printer == null) {
            return CompletableFuture.completedFuture(
                    PrinterResult.failed("Принтер не найден в системе"));
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

        return switch (mimeType.toLowerCase()) {
            case "application/pdf"  -> printPdf(printer, bytes, settings);
            case "image/jpeg"       -> printImage(printer, bytes, DocFlavor.BYTE_ARRAY.JPEG, settings);
            case "image/png"        -> printImage(printer, bytes, DocFlavor.BYTE_ARRAY.PNG, settings);
            default -> CompletableFuture.completedFuture(
                    PrinterResult.failed("Неподдерживаемый тип файла: " + mimeType));
        };
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

    private PrintService resolvePrinter() {
        String configuredName = properties.getPrinter().getName();
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);

        if (configuredName != null && !configuredName.isBlank()) {
            for (PrintService svc : all) {
                if (svc.getName().equalsIgnoreCase(configuredName)) return svc;
                log.info("Printer '{}' capabilities: PDF={}, JPEG={}, PNG={}",
                        svc.getName(),
                        svc.isDocFlavorSupported(DocFlavor.BYTE_ARRAY.PDF),
                        svc.isDocFlavorSupported(DocFlavor.BYTE_ARRAY.JPEG),
                        svc.isDocFlavorSupported(DocFlavor.BYTE_ARRAY.PNG));
            }
            log.warn("Configured printer '{}' not found", configuredName);
        }

        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def != null) return def;
        return all.length > 0 ? all[0] : null;
    }
}