package com.printkiosk.server.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tariffs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TariffEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** {@code null} = глобальный дефолтный тариф. */
    @Column(name = "kiosk_id", length = 50)
    private String kioskId;

    @Column(name = "bw_price_som", nullable = false)
    private int bwPriceSom;

    @Column(name = "color_price_som", nullable = false)
    private int colorPriceSom;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}