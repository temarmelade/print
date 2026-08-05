package com.printkiosk.server.domain;

import com.printkiosk.shared.api.IncidentSeverity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Получатель уведомлений об инцидентах в Telegram.
 *
 * <p>Ключ — {@code chatId}, а не суррогатный id: повторная подписка того же
 * человека должна обновлять запись, а не создавать вторую и слать дубли.
 */
@Entity
@Table(name = "incident_subscribers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class IncidentSubscriberEntity {

    @Id
    @Column(name = "chat_id")
    private Long chatId;

    @Column(length = 120)
    private String label;

    /** DOWN — только блокирующие; WARNING — ещё и предупреждения. */
    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity", nullable = false, length = 16)
    private IncidentSeverity minSeverity;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "last_error", length = 200)
    private String lastError;

    /** Подходит ли инцидент такой тяжести под фильтр подписчика. */
    public boolean wants(IncidentSeverity severity) {
        if (!active) return false;
        // DOWN-подписчику шлём только DOWN; WARNING-подписчику — всё.
        return minSeverity == IncidentSeverity.WARNING || severity == IncidentSeverity.DOWN;
    }
}
