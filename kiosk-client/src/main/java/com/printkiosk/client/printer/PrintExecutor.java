package com.printkiosk.client.printer;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Выделенный executor для I/O-интенсивных операций печати.
 * <p>
 * Назначение: блокирующие операции (ожидание PrintJobListener-событий, чтение
 * файла, сетевое скачивание) не должны выполняться ни на FX-потоке, ни на
 * общем ForkJoinPool.commonPool(), потому что:
 * <ul>
 *   <li>FX-поток заблокирует анимации.</li>
 *   <li>commonPool — для CPU-bound задач; долгое ожидание там блокирует
 *       параллельные стримы и другие CF без явных executor'ов.</li>
 * </ul>
 * Размер пула небольшой: одновременно у нас одна-две задачи печати,
 * больше не понадобится.
 */
@Slf4j
@Component
public class PrintExecutor {

    private final ExecutorService executor;

    public PrintExecutor() {
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                r -> {
                    Thread t = new Thread(r, "print-io-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    public ExecutorService executor() {
        return executor;
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down print executor");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}