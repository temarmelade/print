package com.printkiosk.client.printer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.print.PrintServiceLookup;

/**
 * Готов ли принтер принять задание.
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

    /**
     * Последнее известное состояние. Обновляется ТОЛЬКО фоновой задачей.
     *
     * <p>Опрос принтера — это шесть обходов поддерева SNMP по 2 секунды
     * таймаута с повтором. На отключённом принтере все они уходят в
     * ожидание, и если вызвать проверку из обработчика кнопки, интерфейс
     * замирает на десятки секунд — именно так и выглядел «лаг» вместо
     * появления экрана. Поэтому UI читает готовое значение и никогда не
     * ждёт сеть.
     *
     * <p>Стартовое значение READY выбрано осознанно: до первой проверки
     * лучше пропустить человека дальше (перед оплатой стоит вторая
     * проверка), чем заблокировать печать на исправном киоске из-за того,
     * что фоновый опрос ещё не отработал.
     */
    private volatile Status cached = Status.READY;

    /** Причина, по которой печать невозможна. */
    public enum Status {
        READY,
        /** Принтера нет в системе: не подключён, выключен или нет драйвера. */
        NOT_CONNECTED,
        NO_PAPER,
        PAPER_JAM,
        NO_TONER,
        DOOR_OPEN,
        /** Принтер известен системе, но не отвечает. */
        OFFLINE;

        public boolean isReady() { return this == READY; }

        /** Ключ локализации для сообщения пользователю. */
        public String messageKey() {
            return "printer.error." + name().toLowerCase();
        }
    }

    /** Совместимость с прежними вызовами. */
    public boolean isReady() {
        return status().isReady();
    }

    /**
     * Последнее известное состояние. Не блокирует и не ходит в сеть —
     * безопасно вызывать из потока JavaFX.
     */
    public Status status() {
        return cached;
    }

    /**
     * Фоновое обновление. initialDelay даёт приложению подняться и
     * показать главный экран, не конкурируя за старте с загрузкой UI.
     */
    @Scheduled(initialDelayString = "${kiosk.printer.readiness.initial-delay-ms:3000}",
               fixedDelayString  = "${kiosk.printer.readiness.interval-ms:15000}")
    public void refresh() {
        try {
            Status fresh = evaluate();
            if (fresh != cached) {
                log.info("Состояние принтера изменилось: {} → {}", cached, fresh);
            }
            cached = fresh;
        } catch (Exception e) {
            // Сбой самой проверки не должен ронять планировщик и не должен
            // блокировать киоск: оставляем прежнее значение.
            log.warn("Проверка принтера не удалась: {}", e.toString());
        }
    }

    private Status evaluate() {
        if (PrintServiceLookup.lookupDefaultPrintService() == null
                && PrintServiceLookup.lookupPrintServices(null, null).length == 0) {
            log.warn("Готовность: в системе нет принтеров");
            return Status.NOT_CONNECTED;
        }

        PrinterProbe.Reading r = probe.probe();

        if (r.paperOut())   { log.warn("Готовность: нет бумаги");        return Status.NO_PAPER;   }
        if (r.paperJam())   { log.warn("Готовность: замятие бумаги");    return Status.PAPER_JAM;  }
        if (r.tonerEmpty()) { log.warn("Готовность: тонер закончился");  return Status.NO_TONER;   }
        if (r.doorOpen())   { log.warn("Готовность: открыта крышка");    return Status.DOOR_OPEN;  }
        if (Boolean.FALSE.equals(r.online())) {
            log.warn("Готовность: принтер не отвечает");
            return Status.OFFLINE;
        }

        // Уровни учитываем, только если принтер их реально сообщил.
        if (r.paperPercent() != null && r.paperPercent() < CRITICAL_PCT) {
            log.warn("Готовность: бумаги почти нет ({}%)", r.paperPercent());
            return Status.NO_PAPER;
        }
        if (r.tonerPercent() != null && r.tonerPercent() < CRITICAL_PCT) {
            log.warn("Готовность: тонера почти нет ({}%)", r.tonerPercent());
            return Status.NO_TONER;
        }

        return Status.READY;
    }
}
