package com.printkiosk.client.scanner;

import com.printkiosk.client.service.scan.ScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Доступен ли сканер.
 *
 * <p>Устроен так же, как проверка принтера, и по той же причине: опрос
 * устройства идёт через PowerShell и WIA и занимает секунды. Вызов из
 * обработчика кнопки подвесил бы интерфейс, поэтому состояние обновляет
 * фоновая задача, а экраны читают готовое значение.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScannerReadinessService {

    /**
     * Сколько ждём ответа от устройства. WIA на отключённом сканере может
     * думать долго, и держать ради этого поток планировщика незачем.
     */
    private static final int PROBE_TIMEOUT_SEC = 10;

    private final ScannerService scanner;

    /**
     * Последнее известное состояние.
     *
     * <p>Стартовое true выбрано осознанно: до первой проверки лучше
     * пропустить человека дальше, чем заблокировать сканирование на
     * исправном киоске из-за неотработавшего опроса. Настоящая ошибка
     * всплывёт при самом сканировании.
     */
    private volatile boolean available = true;

    /**
     * Блокировать ли сканирование при недоступном устройстве.
     * false — только для отладки интерфейса без железа.
     */
    @Value("${kiosk.scanner.readiness.enforce:true}")
    private boolean enforce;

    /** Не ходит в устройство — безопасно вызывать из потока JavaFX. */
    public boolean isAvailable() {
        if (!enforce && !available) {
            log.debug("Сканер недоступен, но проверка отключена "
                    + "(kiosk.scanner.readiness.enforce=false)");
            return true;
        }
        return available;
    }

    /** Настоящее состояние, без учёта enforce. */
    public boolean realAvailable() {
        return available;
    }

    @Scheduled(initialDelayString = "${kiosk.scanner.readiness.initial-delay-ms:5000}",
               fixedDelayString  = "${kiosk.scanner.readiness.interval-ms:20000}")
    public void refresh() {
        try {
            Boolean fresh = scanner.isReady()
                    .get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS);

            boolean value = Boolean.TRUE.equals(fresh);
            if (value != available) {
                log.info("Состояние сканера изменилось: {} → {}",
                        available ? "доступен" : "недоступен",
                        value ? "доступен" : "недоступен");
            }
            available = value;

        } catch (Exception e) {
            // Таймаут или ошибка опроса означают, что устройство не
            // отвечает — для человека это то же самое, что его нет.
            if (available) {
                log.warn("Сканер не отвечает: {}", e.toString());
            }
            available = false;
        }
    }
}
