package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.KioskCommandStatus;
import com.printkiosk.shared.api.KioskCommandType;

import java.time.Instant;
import java.util.UUID;

/** Команда киоску — для истории и текущего состояния в админке. */
public record KioskCommandDto(
        UUID id,
        String kioskId,
        KioskCommandType type,
        KioskCommandStatus status,
        String createdBy,
        Instant createdAt,
        Instant dispatchedAt,
        Instant finishedAt,
        String resultMessage
) {}
