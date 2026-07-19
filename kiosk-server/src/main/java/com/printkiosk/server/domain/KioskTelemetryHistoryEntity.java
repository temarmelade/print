package com.printkiosk.server.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Точка истории телеметрии — топливо для предиктивной аналитики. */
@Entity
@Table(name = "kiosk_telemetry_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class KioskTelemetryHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosk_id", nullable = false, length = 64)
    private String kioskId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "toner_percent")
    private Integer tonerPercent;

    @Column(name = "paper_percent")
    private Integer paperPercent;

    @Column(name = "page_counter")
    private Integer pageCounter;
}
