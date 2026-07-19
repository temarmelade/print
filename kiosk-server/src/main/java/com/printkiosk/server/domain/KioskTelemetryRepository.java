package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KioskTelemetryRepository extends JpaRepository<KioskTelemetryEntity, String> {
}
