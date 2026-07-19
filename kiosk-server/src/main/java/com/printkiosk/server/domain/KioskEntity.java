package com.printkiosk.server.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Киоск сети. Схема — миграция V10. */
@Entity
@Table(name = "kiosks")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class KioskEntity {

    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 200)
    private String location;

    private Double latitude;
    private Double longitude;

    /** bcrypt-хеш API-ключа киоска. Сам ключ показывается один раз при выдаче. */
    @Column(name = "api_key_hash", nullable = false, length = 100)
    private String apiKeyHash;

    @Column(name = "paper_capacity", nullable = false)
    private int paperCapacity;

    @Column(name = "cartridge_yield", nullable = false)
    private int cartridgeYield;

    /** Счётчик страниц принтера в момент заправки — база для оценки остатка. */
    @Column(name = "pages_at_paper_refill")
    private Integer pagesAtPaperRefill;

    @Column(name = "pages_at_cartridge_change")
    private Integer pagesAtCartridgeChange;

    @Column(name = "paper_refilled_at")
    private Instant paperRefilledAt;

    @Column(name = "cartridge_changed_at")
    private Instant cartridgeChangedAt;

    @Column(name = "maintenance_mode", nullable = false)
    private boolean maintenanceMode;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
