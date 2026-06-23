package com.printkiosk.client._disabled;

import com.printkiosk.config.KioskProperties;
import com.printkiosk.service.dto.PrinterInfoDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.Attribute;
import javax.print.attribute.standard.*;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class PrinterDiagnosticsService {

    private final KioskProperties kioskProperties;

    public PrinterInfoDto getPrinterInfo() {
        String configuredName = kioskProperties.getPrinter().getName();

        PrintService[] printers = PrintServiceLookup.lookupPrintServices(null, null);
        PrintService defaultPrinter = PrintServiceLookup.lookupDefaultPrintService();

        PrintService selectedPrinter = resolveSelectedPrinter(
                configuredName,
                printers,
                defaultPrinter
        );

        return new PrinterInfoDto(
                configuredName,
                defaultPrinter != null ? defaultPrinter.getName() : null,
                Arrays.stream(printers)
                        .map(PrintService::getName)
                        .toList(),
                selectedPrinter != null ? selectedPrinter.getName() : null,
                selectedPrinter != null,
                supports(selectedPrinter, Copies.class, new Copies(1)),
                supports(selectedPrinter, Chromaticity.class, Chromaticity.MONOCHROME),
                supports(selectedPrinter, Sides.class, Sides.DUPLEX),
                supports(selectedPrinter, Sides.class, Sides.ONE_SIDED),
                supports(selectedPrinter, MediaSizeName.class, MediaSizeName.ISO_A4),
                supports(selectedPrinter, OrientationRequested.class, OrientationRequested.PORTRAIT),
                supports(selectedPrinter, OrientationRequested.class, OrientationRequested.LANDSCAPE)
        );
    }

    private PrintService resolveSelectedPrinter(
            String configuredName,
            PrintService[] printers,
            PrintService defaultPrinter
    ) {
        if (configuredName != null && !configuredName.isBlank()) {
            return Arrays.stream(printers)
                    .filter(printer -> printer.getName().equalsIgnoreCase(configuredName))
                    .findFirst()
                    .orElse(defaultPrinter);
        }

        if (defaultPrinter != null) {
            return defaultPrinter;
        }

        return printers.length > 0 ? printers[0] : null;
    }

    private boolean supports(
            PrintService printer,
            Class<?> category,
            Attribute attribute
    ) {
        if (printer == null) {
            return false;
        }

        try {
            return printer.isAttributeValueSupported(attribute, null, null);
        } catch (Exception e) {
            return false;
        }
    }
}
