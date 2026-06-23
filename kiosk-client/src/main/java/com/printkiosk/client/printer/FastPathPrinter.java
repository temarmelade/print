package com.printkiosk.client.printer;

import com.printkiosk.shared.api.dto.PrintSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.print.DocFlavor;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import java.util.concurrent.CompletableFuture;

/**
 * Быстрый путь: принтер сам растеризует PDF/JPEG/PNG. CPU киоска свободен.
 * Применим, когда {@link PrintService#isDocFlavorSupported(DocFlavor)}
 * для нужного flavor'а возвращает {@code true}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FastPathPrinter {

    private final PrinterDriver driver;
    private final PrintAttributesBuilder attributesBuilder;

    public CompletableFuture<PrinterResult> print(PrintService printer,
                                                  byte[] bytes,
                                                  DocFlavor flavor,
                                                  PrintSettings settings) {
        log.info("Fast path: sending {} bytes as {} to '{}'",
                bytes.length, flavor.getMimeType(), printer.getName());

        return driver.print(
                printer,
                new SimpleDoc(bytes, flavor, null),
                attributesBuilder.build(settings, printer));
    }
}