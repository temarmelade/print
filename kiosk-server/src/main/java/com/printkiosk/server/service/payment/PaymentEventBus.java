package com.printkiosk.server.service.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory pub/sub для событий смены статуса платежа.
 * <p>
 * Подписчики ключатся по PIN (это удобно, потому что клиент знает только PIN,
 * а не jobId). При публикации находим всех подписчиков на этот PIN и шлём им
 * событие — обычно один (один киоск ждёт оплату по PIN).
 * <p>
 * Реализация в памяти — этого достаточно для одного инстанса сервера.
 * Когда сервер будет горизонтально масштабироваться, переключим на Redis Pub/Sub
 * или Kafka — контракт {@link PaymentEvent} останется тем же.
 */
@Slf4j
@Component
public class PaymentEventBus {

    private final Map<String, Set<Consumer<PaymentEvent>>> subscribers = new ConcurrentHashMap<>();

    /** Подписаться на события по PIN. Возвращает функцию отписки. */
    public Runnable subscribe(String pin, Consumer<PaymentEvent> handler) {
        subscribers.computeIfAbsent(pin, k -> ConcurrentHashMap.newKeySet()).add(handler);
        log.debug("Subscribed to PIN events: pin={}, total subs={}",
                maskPin(pin), subscribers.get(pin).size());

        return () -> {
            Set<Consumer<PaymentEvent>> set = subscribers.get(pin);
            if (set != null) {
                set.remove(handler);
                if (set.isEmpty()) subscribers.remove(pin);
            }
        };
    }

    /** Опубликовать событие — вызывается из PaymentService при смене статуса. */
    public void publish(PaymentEvent event) {
        Set<Consumer<PaymentEvent>> set = subscribers.get(event.pin());
        if (set == null || set.isEmpty()) {
            log.debug("No subscribers for pin={}", maskPin(event.pin()));
            return;
        }
        log.info("Publishing {} for pin={} to {} subscribers",
                event.type(), maskPin(event.pin()), set.size());

        // Копия перед итерацией: subscriber может вызвать отписку,
        // что модифицирует исходный set.
        for (Consumer<PaymentEvent> handler : Set.copyOf(set)) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                log.warn("Subscriber threw during event dispatch", e);
            }
        }
    }

    private static String maskPin(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }
}