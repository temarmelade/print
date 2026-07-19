package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KioskTelemetryHistoryRepository
        extends JpaRepository<KioskTelemetryHistoryEntity, Long> {
}
