package com.printkiosk.server.domain;

import com.printkiosk.shared.api.PrintJobStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintJobEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * LAZY: при типичных операциях (markPaid, markPrinting) данные файла
     * не нужны, незачем тянуть лишнюю строку из БД. Когда нужны — есть
     * явный JOIN FETCH в репозитории.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false, updatable = false)
    private FileEntity file;

    // ── Print settings ──────────────────────────────────────────────
    @Column(nullable = false)
    private int copies;

    @Column(name = "color_mode", nullable = false, length = 20)
    private String colorMode;       // лучше сделать enum в shared (см. ниже)

    @Column(name = "double_sided", nullable = false)
    private boolean doubleSided;

    @Column(nullable = false, length = 20)
    private String orientation;     // тоже кандидат на enum

    @Column(name = "paper_size", nullable = false, length = 20)
    private String paperSize;       // и этот

    // ── Pricing ─────────────────────────────────────────────────────
    @Column(name = "price_som", nullable = false)
    private int priceSom;

    // ── Payment ─────────────────────────────────────────────────────
    @Column(name = "payment_id", length = 100, unique = true)
    private String paymentId;

    @Column(name = "payment_url", length = 500)
    private String paymentUrl;

    @Column(name = "payment_status", length = 20)
    private String paymentStatus;   // PENDING / PAID / FAILED / CANCELLED

    // ── State ───────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PrintJobStatus status;

    // ── Audit ───────────────────────────────────────────────────────
    @Column(name = "kiosk_id", length = 50)
    private String kioskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}