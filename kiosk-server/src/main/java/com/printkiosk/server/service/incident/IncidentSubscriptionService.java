package com.printkiosk.server.service.incident;

import com.printkiosk.server.config.TelegramBotProperties;
import com.printkiosk.server.domain.IncidentSubscriberEntity;
import com.printkiosk.server.domain.IncidentSubscriberRepository;
import com.printkiosk.shared.api.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Подписка на уведомления об инцидентах.
 *
 * <p>Бот один и тот же для клиентов и для персонала, поэтому подписка закрыта
 * кодом доступа: иначе любой пользователь, печатающий рефераты, мог бы узнать
 * о неисправностях сети и, например, о том, что точка осталась без присмотра.
 * Код задаётся в конфиге и сравнивается устойчиво ко времени.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncidentSubscriptionService {

    private final IncidentSubscriberRepository subscribers;
    private final TelegramBotProperties properties;

    /** Совпадает ли присланный код с настроенным. */
    public boolean isValidToken(String token) {
        String expected = properties.getAlertsToken();
        if (expected == null || expected.isBlank()) {
            log.warn("Попытка подписки, но telegram.bot.alerts-token не задан");
            return false;
        }
        if (token == null) return false;
        // Сравнение постоянного времени: код короткий, и посимвольный выход
        // теоретически позволяет подобрать его перебором.
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Transactional
    public void subscribe(Long chatId, String label, IncidentSeverity minSeverity) {
        IncidentSubscriberEntity s = subscribers.findById(chatId)
                .orElseGet(() -> IncidentSubscriberEntity.builder()
                        .chatId(chatId)
                        .createdAt(Instant.now())
                        .build());

        s.setLabel(trim(label, 120));
        s.setMinSeverity(minSeverity);
        s.setActive(true);
        s.setLastError(null);
        subscribers.save(s);

        log.info("Подписка на инциденты: chatId={} label={} severity={}",
                chatId, label, minSeverity);
    }

    /** Меняет фильтр, не трогая саму подписку. */
    @Transactional
    public boolean changeSeverity(Long chatId, IncidentSeverity minSeverity) {
        return subscribers.findById(chatId)
                .filter(IncidentSubscriberEntity::isActive)
                .map(s -> {
                    s.setMinSeverity(minSeverity);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean unsubscribe(Long chatId) {
        return subscribers.findById(chatId)
                .filter(IncidentSubscriberEntity::isActive)
                .map(s -> {
                    s.setActive(false);
                    log.info("Отписка от инцидентов: chatId={}", chatId);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isSubscribed(Long chatId) {
        return subscribers.findById(chatId)
                .map(IncidentSubscriberEntity::isActive)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<IncidentSubscriberEntity> activeSubscribers() {
        return subscribers.findByActiveTrue();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
