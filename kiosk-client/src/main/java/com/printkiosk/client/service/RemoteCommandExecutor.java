package com.printkiosk.client.service;

import com.printkiosk.shared.api.KioskCommandType;
import com.printkiosk.shared.api.dto.TelemetryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Исполняет команды, пришедшие с сервера в ответе на heartbeat.
 *
 * <p>Порядок действий важен: сначала подтверждаем команду серверу, потом
 * перезагружаемся. Наоборот не выйдет — после {@code shutdown /r} процесса
 * уже нет и подтверждать некому, команда навсегда зависла бы в SENT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteCommandExecutor {

    /** Отсрочка перед перезагрузкой: успеть отправить подтверждение и дописать логи. */
    private static final int REBOOT_DELAY_SEC = 15;

    private final KioskActivityState activity;

    /** Защита от повторного запуска, если ответ придёт дважды. */
    private final AtomicBoolean executing = new AtomicBoolean(false);

    @Value("${kiosk.commands.enabled:true}")
    private boolean enabled;

    /**
     * @return текст отказа, если команду выполнять нельзя; null — принята
     */
    public String tryExecute(TelemetryResponse response, Runnable ackAccepted) {
        if (!enabled) return "Дистанционные команды отключены в конфигурации киоска";
        if (!response.hasCommand()) return null;

        if (activity.isBusy()) {
            // Не откладываем «на потом»: оператор увидит отказ и решит сам.
            // Тихая отложенная перезагрузка хуже — она сработает в
            // непредсказуемый момент.
            return "Киоск обслуживает клиента — перезагрузка отменена, повторите позже";
        }

        if (!executing.compareAndSet(false, true)) {
            return "Команда уже выполняется";
        }

        KioskCommandType type = response.command();
        log.warn("Получена команда {} — выполняем", type);

        // Сначала подтверждение, потом сама перезагрузка.
        ackAccepted.run();

        try {
            switch (type) {
                case RESTART_APP -> restartApp();
                case REBOOT_OS   -> rebootOs();
            }
        } catch (Exception e) {
            log.error("Не удалось выполнить команду {}", type, e);
            executing.set(false);
            return "Ошибка выполнения: " + e.getMessage();
        }
        return null;
    }

    /**
     * Мягкий перезапуск: гасим приложение и отдаём подъём внешнему
     * сторожу — службе Windows или ярлыку в автозагрузке с перезапуском.
     * Поднимать себя из умирающего процесса ненадёжно, поэтому этим
     * занимается тот, кто нас запускал.
     */
    private void restartApp() {
        log.warn("Перезапуск приложения по команде с сервера");
        new Thread(() -> {
            sleep(2000);                    // дать подтверждению уйти по сети
            System.exit(42);                // код 42 = «перезапусти меня»
        }, "restart-app").start();
    }

    /** Полная перезагрузка Windows с отсрочкой и записью причины в журнал. */
    private void rebootOs() {
        log.warn("Перезагрузка Windows через {} сек по команде с сервера", REBOOT_DELAY_SEC);
        new Thread(() -> {
            sleep(2000);
            try {
                new ProcessBuilder(
                        "shutdown", "/r",
                        "/t", String.valueOf(REBOOT_DELAY_SEC),
                        "/c", "PrintKiosk: remote reboot from admin panel")
                        .start();
            } catch (Exception e) {
                log.error("shutdown /r не запустился", e);
            }
        }, "reboot-os").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
