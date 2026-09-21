package com.printkiosk.client.printer;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Поиск принтера киоска среди принтеров Windows. Правило одно: печатаем
 * только на принтер из {@code kiosk.printer.name}.
 *
 * <p>Раньше при ненайденном имени задание уходило в принтер по умолчанию.
 * У новой учётной записи Windows это «Microsoft Print to PDF» — после
 * оплаты на экране открывалось окно «Сохранение результата печати», а
 * документ не печатался. Теперь: указанный принтер не найден — значит,
 * принтера нет, и киоск не принимает оплату (см. PrinterReadinessService).
 *
 * <p>Имя сравнивается без учёта регистра и пробелов по краям.
 */
public final class PrinterLocator {

    /** Признаки виртуальных принтеров: они не печатают на бумаге. */
    private static final List<String> VIRTUAL_MARKERS = List.of(
            "pdf", "xps", "onenote", "document writer", "fax", "факс");

    private PrinterLocator() {}

    /**
     * @param configuredName имя из {@code kiosk.printer.name}
     * @return принтер или null, если его нет в Windows
     */
    public static PrintService find(String configuredName) {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);

        if (configuredName != null && !configuredName.isBlank()) {
            String wanted = configuredName.trim();
            for (PrintService s : all) {
                if (s.getName().trim().equalsIgnoreCase(wanted)) return s;
            }
            // Имя указано, но такого принтера нет — никаких «запасных».
            return null;
        }

        // Имя не указано (разработка): принтер по умолчанию, если он настоящий,
        // иначе единственный настоящий. Виртуальные не берём никогда.
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def != null && !isVirtual(def)) return def;
        List<PrintService> real = Arrays.stream(all).filter(s -> !isVirtual(s)).toList();
        return real.size() == 1 ? real.get(0) : null;
    }

    /** «Microsoft Print to PDF», «XPS Document Writer», «OneNote», «Fax»… */
    public static boolean isVirtual(PrintService service) {
        String name = service.getName().toLowerCase(Locale.ROOT);
        return VIRTUAL_MARKERS.stream().anyMatch(name::contains);
    }

    /** Имена всех принтеров Windows — для лога, чтобы сразу видеть опечатку в конфиге. */
    public static String availableNames() {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        if (all.length == 0) return "(в Windows нет ни одного принтера)";
        return Arrays.stream(all)
                .map(s -> "«" + s.getName() + "»" + (isVirtual(s) ? " (виртуальный)" : ""))
                .collect(Collectors.joining(", "));
    }
}
