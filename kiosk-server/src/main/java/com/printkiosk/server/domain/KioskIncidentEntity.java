package com.printkiosk.server.domain;

import com.printkiosk.shared.api.IncidentSeverity;
import com.printkiosk.shared.api.IncidentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Инцидент киоска — период, когда киоск был в проблемном состоянии.
 *
 * <p>Открыт, пока {@code resolvedAt == null}. Тождество инцидента задаётся
 * парой (киоск, тип), а не текстом причины: причина содержит меняющиеся
 * значения и на каждом heartbeat выглядит иначе.
 */
@Entity
@Table(name = "kiosk_incidents")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class KioskIncidentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosk_id", nullable = false, length = 64)
    private String kioskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 32)
    private IncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentSeverity severity;

    /** Снимок причины на момент открытия — для показа, не для сравнения. */
    @Column(length = 300)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** null = инцидент ещё не закрыт. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Сколько heartbeat'ов подтвердили проблему. */
    @Column(nullable = false)
    private int occurrences;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by", length = 120)
    private String acknowledgedBy;

    public boolean isOpen() {
        return resolvedAt == null;
    }
}
