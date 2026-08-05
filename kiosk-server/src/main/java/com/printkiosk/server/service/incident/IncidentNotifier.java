package com.printkiosk.server.service.incident;

import com.printkiosk.server.domain.IncidentSubscriberEntity;
import com.printkiosk.server.domain.IncidentSubscriberRepository;
import com.printkiosk.server.integration.telegram.TelegramPrintBot;
import com.printkiosk.shared.api.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Рассылка уведомлений об инцидентах в Telegram.
 *
 * <p>Слушает события {@link IncidentEvents} и срабатывает <b>после коммита</b>
 * ({@link TransactionPhase#AFTER_COMMIT}). Это принципиально: отправленное
 * сообщение нельзя отозвать, поэтому уведомлять о том, что ещё может
 * откатиться, нельзя — техник поехал бы чинить несуществующую поломку.
 *
 * <p>Отправка асинхронная: приём телеметрии не должен ждать сетевой вызов к
 * Telegram. Если бот отключён или недоступен, инцидент всё равно записан в
 * базу и виден в админке — уведомления это удобство, а не источник правды.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncidentNotifier {

    /**
     * Пауза между сообщениями. Telegram ограничивает скорость (~30 сообщений
     * в секунду суммарно) и при массовом сбое — когда офлайн уходит вся сеть
     * киосков разом — можно легко упереться в лимит и получить 429.
     */
    private static final Duration SEND_DELAY = Duration.ofMillis(120);

    private final IncidentSubscriberRepository subscribers;
    private final TelegramPrintBot bot;
    private final IncidentMessageFormatter formatter;

    // ════════════════════════════════════════════════════════════════
    //  События
    // ════════════════════════════════════════════════════════════════

    /**
     * Транзакция объявлена на самом слушателе, а не на {@code broadcast}:
     * вызов соседнего метода того же бина идёт мимо прокси Spring, и
     * аннотация там просто не сработала бы — отметки о доставке не
     * сохранились бы. REQUIRES_NEW потому, что исходная транзакция к этому
     * моменту уже закоммичена и своей транзакции нет.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(IncidentEvents.Opened event) {
        broadcast(event.severity(), formatter.opened(event));
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(IncidentEvents.Resolved event) {
        // О снятии предупреждений не пишем: «бумага снова в норме» — шум.
        // Важно закрытие того, что реально останавливало киоск.
        if (event.severity() != IncidentSeverity.DOWN) return;
        broadcast(event.severity(), formatter.resolved(event));
    }

    // ════════════════════════════════════════════════════════════════
    //  Рассылка
    // ════════════════════════════════════════════════════════════════

    private void broadcast(IncidentSeverity severity, String text) {
        List<IncidentSubscriberEntity> targets = subscribers.findByActiveTrue().stream()
                .filter(s -> s.wants(severity))
                .toList();

        if (targets.isEmpty()) {
            log.debug("Нет подписчиков для уведомления (severity={})", severity);
            return;
        }

        for (IncidentSubscriberEntity s : targets) {
            deliver(s, text);
            pause();
        }
    }

    private void deliver(IncidentSubscriberEntity s, String text) {
        try {
            bot.sendNotification(s.getChatId(), text);
            s.setLastSentAt(Instant.now());
            s.setLastError(null);

        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            s.setLastError(trim(message, 200));

            // Пользователь заблокировал бота или удалил чат — слать больше
            // некуда. Гасим подписку, иначе будем биться в неё вечно.
            if (isUnreachable(message)) {
                s.setActive(false);
                log.warn("Подписка {} отключена: чат недоступен ({})", s.getChatId(), message);
            } else {
                log.warn("Не удалось уведомить {}: {}", s.getChatId(), message);
            }
        }
    }

    /** Ошибки, при которых повторные попытки бессмысленны. */
    private static boolean isUnreachable(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("bot was blocked")
                || m.contains("chat not found")
                || m.contains("user is deactivated")
                || m.contains("bot was kicked");
    }

    private static void pause() {
        try {
            Thread.sleep(SEND_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
