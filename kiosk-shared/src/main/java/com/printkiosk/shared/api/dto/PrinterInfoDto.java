package com.printkiosk.shared.api.dto;

import java.util.List;

public record PrinterInfoDto(
        String configuredPrinterName,
        String defaultPrinterName,
        List<String> availablePrinters,
        String selectedPrinterName,
        boolean printerAvailable,
        boolean supportsCopies,
        boolean supportsMonochrome,
        boolean supportsDuplex,
        boolean supportsOneSided,
        boolean supportsA4,
        boolean supportsPortrait,
        boolean supportsLandscape
) {
}