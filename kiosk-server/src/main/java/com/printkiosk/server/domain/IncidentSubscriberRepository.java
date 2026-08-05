package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentSubscriberRepository
        extends JpaRepository<IncidentSubscriberEntity, Long> {

    List<IncidentSubscriberEntity> findByActiveTrue();
}
