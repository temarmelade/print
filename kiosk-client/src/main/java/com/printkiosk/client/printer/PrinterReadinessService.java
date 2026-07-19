package com.printkiosk.client.printer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.print.PrintServiceLookup;

/**
 * Готов ли принтер принять задание (проверяется перед оплатой).
 *
 * <p>Философия: блокируем оплату только тогда, когда печать ТОЧНО не пройдёт
 * (нет бумаги, замятие, пустой тонер, открыта крышка). Неизвестные уровни
 * расходников печать не блокируют — MF232w может их вовсе не отдавать, и
 * глушить киоск из-за «не знаю» значило бы терять деньги на ровном месте.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterReadinessService {

    /** Ниже этого процента печатать не начинаем — рискуем оборвать задание. */
    private static final int CRITICAL_PCT = 3;

    private final PrinterProbe probe;

    public boolean isReady() {
        if (PrintServiceLookup.lookupDefaultPrintService() == null
                && PrintServiceLookup.lookupPrintServices(null, null).length == 0) {
            log.warn("Готовность: в системе нет принтеров");
            return false;
        }

        PrinterProbe.Reading r = probe.probe();

        if (r.paperOut())   { log.warn("Готовность: нет бумаги");        return false; }
        if (r.paperJam())   { log.warn("Готовность: замятие бумаги");    return false; }
        if (r.tonerEmpty()) { log.warn("Готовность: тонер закончился");  return false; }
        if (r.doorOpen())   { log.warn("Готовность: открыта крышка");    return false; }
        if (Boolean.FALSE.equals(r.online())) {
            log.warn("Готовность: принтер не отвечает");
            return false;
        }

        // Уровни учитываем, только если принтер их реально сообщил.
        if (r.paperPercent() != null && r.paperPercent() < CRITICAL_PCT) {
            log.warn("Готовность: бумаги почти нет ({}%)", r.paperPercent());
            return false;
        }
        if (r.tonerPercent() != null && r.tonerPercent() < CRITICAL_PCT) {
            log.warn("Готовность: тонера почти нет ({}%)", r.tonerPercent());
            return false;
        }

        return true;
    }
}
