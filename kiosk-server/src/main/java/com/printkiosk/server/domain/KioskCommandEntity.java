package com.printkiosk.server.domain;

import com.printkiosk.shared.api.KioskCommandStatus;
import com.printkiosk.shared.api.KioskCommandType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Команда киоску. Живёт в очереди, пока киоск не заберёт её на heartbeat. */
@Entity
@Table(name = "kiosk_commands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KioskCommandEntity {

    @Id
    private UUID id;

    @Column(name = "kiosk_id", nullable = false, length = 64)
    private String kioskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KioskCommandType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KioskCommandStatus status;

    /** Логин оператора — перезагрузка точки должна быть именной. */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Когда киоск забрал команду. */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    /** Когда команда пришла к терминальному статусу. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "result_message", columnDefinition = "TEXT")
    private String resultMessage;
}
