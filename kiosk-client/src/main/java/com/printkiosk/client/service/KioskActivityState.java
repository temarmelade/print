package com.printkiosk.client.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Занят ли киоск обслуживанием клиента прямо сейчас.
 *
 * <p>Нужен, чтобы дистанционная перезагрузка не прилетела посреди чужой
 * оплаты или печати: деньги уже списаны, а документ ещё не вышел —
 * перезагрузка в этот момент означает жалобу и возврат.
 *
 * <p>Состояние обновляет {@code MainController.changeStep}. Отдельный бин,
 * а не поле контроллера, потому что контроллер прототипный и живёт в
 * JavaFX-потоке, а читать состояние надо из фонового планировщика.
 */
@Slf4j
@Component
public class KioskActivityState {

    private final AtomicBoolean busy = new AtomicBoolean(false);

    public void setBusy(boolean value) {
        if (busy.getAndSet(value) != value) {
            log.debug("Киоск {}", value ? "занят клиентом" : "свободен");
        }
    }

    public boolean isBusy() {
        return busy.get();
    }
}
