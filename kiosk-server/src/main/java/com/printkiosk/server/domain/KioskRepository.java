package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KioskRepository extends JpaRepository<KioskEntity, String> {
    List<KioskEntity> findAllByOrderByNameAsc();
}
