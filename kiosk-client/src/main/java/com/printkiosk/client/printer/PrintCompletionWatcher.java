package com.printkiosk.client.printer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Ждёт, пока листы физически выйдут из принтера.
 *
 * <p>Windows сообщает только о том, что задание принято в очередь печати, —
 * это происходит раньше, чем выйдет бумага. Точнее момент окончания виден по
 * счётчику напечатанных страниц принтера (SNMP, prtMarkerLifeCount): снимаем
 * его до отправки задания и ждём прироста на число страниц задания.
 *
 * <p>Ожидание никогда не превращает печать в ошибку: задание к этому моменту
 * уже успешно передано. Если счётчик недоступен или ведёт себя не так, как
 * ожидалось, просто перестаём ждать — худший случай ограничен сверху.
 *
 * <p>Когда ожидание прекращается:
 * <ul>
 *   <li>счётчик вырос на ожидаемое число страниц — нормальный случай;</li>
 *   <li>счётчик рос, но замер на {@link #STALL_TIMEOUT} — например, при
 *       двусторонней печати принтер считает листы, а не стороны;</li>
 *   <li>счётчик не сдвинулся за {@link #FIRST_PAGE_TIMEOUT} — принтер не
 *       отдаёт счётчик в реальном времени;</li>
 *   <li>общий предел: {@link #BASE_CAP} + {@link #PER_PAGE_CAP} на страницу.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrintCompletionWatcher {

    private static final long POLL_MS = 1_000;
    /** Первый лист: выход из сна и прогрев MF232w — до ~15–20 с. */
    private static final Duration FIRST_PAGE_TIMEOUT = Duration.ofSeconds(40);
    /** Пауза без новых страниц, после которой считаем печать законченной. */
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration BASE_CAP = Duration.ofSeconds(60);
    private static final Duration PER_PAGE_CAP = Duration.ofSeconds(8);

    private final PrinterProbe probe;
    private final PrintExecutor printExecutor;

    /**
     * Показание счётчика до отправки задания.
     *
     * @return null — отслеживать нечем (SNMP выключен или принтер не ответил)
     */
    public Integer snapshot() {
        return probe.readPageCounter();
    }

    /**
     * @param baseline      показание {@link #snapshot()} до отправки задания
     * @param expectedPages сколько страниц должно выйти (страницы × копии);
     *                      0 — неизвестно, не ждём
     */
    public CompletableFuture<Void> awaitPrinted(Integer baseline, int expectedPages) {
        if (baseline == null || expectedPages <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> waitForCounter(baseline, expectedPages),
                printExecutor.executor());
    }

    private void waitForCounter(int baseline, int expected) {
        long start = System.nanoTime();
        long cap = BASE_CAP.plus(PER_PAGE_CAP.multipliedBy(expected)).toNanos();
        int last = baseline;
        long lastChange = start;

        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long now = System.nanoTime();

            Integer current = probe.readPageCounter();
            if (current != null) {
                if (current < baseline) {
                    // Счётчик монотонный; меньше исходного — читаем не ту
                    // запись, отслеживать по нему нельзя.
                    log.warn("Счётчик страниц уменьшился ({} → {}) — не ждём", baseline, current);
                    return;
                }
                if (current > last) {
                    last = current;
                    lastChange = now;
                }
                if (last - baseline >= expected) {
                    log.info("Принтер выдал {} стр. за {} с", last - baseline, seconds(now - start));
                    return;
                }
            }

            if (last == baseline && now - start > FIRST_PAGE_TIMEOUT.toNanos()) {
                log.warn("Счётчик страниц не изменился за {} с — считаем печать завершённой",
                        FIRST_PAGE_TIMEOUT.toSeconds());
                return;
            }
            if (last > baseline && now - lastChange > STALL_TIMEOUT.toNanos()) {
                log.info("Новых страниц нет {} с (вышло {} из {}) — считаем печать завершённой",
                        STALL_TIMEOUT.toSeconds(), last - baseline, expected);
                return;
            }
            if (now - start > cap) {
                log.warn("Предел ожидания печати {} с (вышло {} из {})",
                        seconds(cap), last - baseline, expected);
                return;
            }
        }
    }

    private static long seconds(long nanos) {
        return Duration.ofNanos(nanos).toSeconds();
    }
}
