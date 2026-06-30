package com.printkiosk.server.domain;

import com.printkiosk.shared.api.AdMediaType;
import com.printkiosk.shared.api.AdSlot;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ad_creatives")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AdCreative {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 10)
    private AdMediaType mediaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AdSlot slot;

    @Column(name = "stored_filename", nullable = false, unique = true, length = 80)
    private String storedFilename;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
