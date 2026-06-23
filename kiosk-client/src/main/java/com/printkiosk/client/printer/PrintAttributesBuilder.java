package com.printkiosk.client.printer;

import com.printkiosk.shared.api.dto.PrintSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.print.PrintService;
import javax.print.attribute.Attribute;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.*;

/**
 * Строит {@link PrintRequestAttributeSet} из {@link PrintSettings},
 * добавляя только те атрибуты, которые поддерживает целевой принтер.
 */
@Slf4j
@Component
public class PrintAttributesBuilder {

    public PrintRequestAttributeSet build(PrintSettings settings, PrintService printer) {
        HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

        addIfSupported(printer, attrs, new Copies(Math.max(settings.copies(), 1)));

        if ("A4".equalsIgnoreCase(settings.paperSize())) {
            addIfSupported(printer, attrs, MediaSizeName.ISO_A4);
        }

        addIfSupported(printer, attrs,
                "LANDSCAPE".equalsIgnoreCase(settings.orientation())
                        ? OrientationRequested.LANDSCAPE
                        : OrientationRequested.PORTRAIT);

        addIfSupported(printer, attrs,
                settings.doubleSided() ? Sides.DUPLEX : Sides.ONE_SIDED);

        addIfSupported(printer, attrs,
                "COLOR".equalsIgnoreCase(settings.colorMode())
                        ? Chromaticity.COLOR
                        : Chromaticity.MONOCHROME);

        return attrs;
    }

    private void addIfSupported(PrintService printer,
                                HashPrintRequestAttributeSet attrs,
                                Attribute attr) {
        try {
            if (printer.isAttributeValueSupported(attr, null, null)) {
                attrs.add(attr);
            } else {
                log.debug("Printer '{}' does not support: {}", printer.getName(), attr);
            }
        } catch (Exception e) {
            log.debug("Could not check attribute: {}", attr, e);
        }
    }
}