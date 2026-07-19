package com.printkiosk.server.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Текущее состояние киоска (перезаписывается каждым heartbeat).
 * Integer/Boolean, а не примитивы: null = «принтер не сообщает».
 */
@Entity
@Table(name = "kiosk_telemetry")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class KioskTelemetryEntity {

    @Id
    @Column(name = "kiosk_id", length = 64, nullable = false)
    private String kioskId;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "client_version", length = 40)
    private String clientVersion;

    @Column(name = "printer_online")
    private Boolean printerOnline;

    @Column(name = "toner_percent")
    private Integer tonerPercent;

    @Column(name = "paper_percent")
    private Integer paperPercent;

    @Column(name = "paper_source", length = 16)
    private String paperSource;

    @Column(name = "toner_source", length = 16)
    private String tonerSource;

    @Column(name = "paper_out", nullable = false)
    private boolean paperOut;

    @Column(name = "paper_jam", nullable = false)
    private boolean paperJam;

    @Column(name = "toner_low", nullable = false)
    private boolean tonerLow;

    @Column(name = "toner_empty", nullable = false)
    private boolean tonerEmpty;

    @Column(name = "door_open", nullable = false)
    private boolean doorOpen;

    @Column(name = "printer_error", length = 200)
    private String printerError;

    @Column(name = "page_counter")
    private Integer pageCounter;
}
