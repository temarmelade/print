package com.printkiosk.server.service.incident;

import com.printkiosk.shared.api.IncidentSeverity;
import com.printkiosk.shared.api.IncidentType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Тексты уведомлений об инцидентах.
 *
 * <p>Пишем на русском и без Markdown: имя киоска и текст ошибки от драйвера
 * приходят извне и могут содержать символы разметки ({@code _}, {@code *}),
 * из-за которых Telegram отклонит сообщение целиком. Обычный текст надёжнее
 * красивого.
 */
@Component
public class IncidentMessageFormatter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Bishkek");
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd.MM HH:mm").withZone(ZONE);

    public String opened(IncidentEvents.Opened e) {
        StringBuilder sb = new StringBuilder();

        sb.append(icon(e.type(), e.severity())).append(' ')
          .append(e.severity() == IncidentSeverity.DOWN ? "КИОСК НЕ РАБОТАЕТ" : "Предупреждение")
          .append('\n').append('\n');

        sb.append(e.kioskName());
        if (e.location() != null && !e.location().isBlank()) {
            sb.append(" — ").append(e.location());
        }
        sb.append('\n');

        sb.append("Проблема: ").append(e.type().title()).append('\n');
        if (e.reason() != null && !e.reason().isBlank()
                && !e.reason().equals(e.type().title())) {
            sb.append("Детали: ").append(e.reason()).append('\n');
        }
        sb.append("Время: ").append(TIME.format(e.startedAt()));

        return sb.toString();
    }

    public String resolved(IncidentEvents.Resolved e) {
        return "✅ Киоск снова работает\n\n"
                + e.kioskName() + '\n'
                + "Было: " + e.type().title() + '\n'
                + "Простой: " + duration(e.durationMinutes());
    }

    /** Ответ на команду /status, когда всё в порядке. */
    public String allClear() {
        return "✅ Открытых инцидентов нет — все киоски работают.";
    }

    public String subscribed(IncidentSeverity minSeverity) {
        return "🔔 Уведомления включены.\n\n"
                + (minSeverity == IncidentSeverity.DOWN
                   ? "Буду писать, когда киоск перестанет работать."
                   : "Буду писать и о предупреждениях (заканчиваются расходники).")
                + "\n\nКоманды:\n"
                + "/status — что сейчас сломано\n"
                + "/alerts all — включить предупреждения\n"
                + "/alerts off — отключить уведомления";
    }

    public String unsubscribed() {
        return "🔕 Уведомления отключены. Чтобы вернуть — отправьте команду с кодом доступа снова.";
    }

    public String accessDenied() {
        return "Неверный код доступа.";
    }

    public String notSubscribed() {
        return "Вы не подписаны на уведомления.";
    }

    /** Короткая строка инцидента для сводки /status. */
    public String statusLine(String kioskName, IncidentType type,
                             IncidentSeverity severity, long minutes) {
        return icon(type, severity) + " " + kioskName
                + " — " + type.title()
                + " (" + duration(minutes) + ")";
    }

    // ── Внутреннее ──

    private static String icon(IncidentType type, IncidentSeverity severity) {
        if (severity == IncidentSeverity.WARNING) return "🟡";
        return switch (type) {
            case OFFLINE -> "📡";
            case PAPER_JAM, PAPER_OUT -> "📄";
            case TONER_EMPTY -> "🖨";
            case DOOR_OPEN -> "🚪";
            default -> "🔴";
        };
    }

    /** «2 ч 15 мин» читается мгновенно, «135 мин» требует счёта в уме. */
    public static String duration(long minutes) {
        if (minutes < 1) return "меньше минуты";
        if (minutes < 60) return minutes + " мин";
        long h = minutes / 60;
        long m = minutes % 60;
        if (h < 24) return m > 0 ? h + " ч " + m + " мин" : h + " ч";
        return (h / 24) + " д " + (h % 24) + " ч";
    }

    public static long minutesSince(Instant from) {
        return Math.max(Duration.between(from, Instant.now()).toMinutes(), 0);
    }
}
