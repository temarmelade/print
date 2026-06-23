package com.printkiosk.server.domain;

import com.printkiosk.shared.api.UploadSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "files")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class FileEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;                       // UUIDv7

    @Column(nullable = false, length = 4, unique = true)
    private String code;

    @Column(name = "stored_filename", nullable = false, unique = true, length = 80)
    private String storedFilename;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadSource source;           // TELEGRAM, WEBSITE

    @Column(name = "telegram_user_id")
    private Long telegramUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;


}
