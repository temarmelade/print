package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface KioskTelemetryHistoryRepository
        extends JpaRepository<KioskTelemetryHistoryEntity, Long> {

    /** Точки истории киоска за период — по ним считается темп расхода. */
    List<KioskTelemetryHistoryEntity>
        findByKioskIdAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(
            String kioskId, Instant from);
}
