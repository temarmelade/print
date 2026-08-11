package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.AdMediaType;
import com.printkiosk.shared.api.AdSlot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdCreativeDto(
        UUID id,
        String title,
        AdMediaType mediaType,
        AdSlot slot,
        String mediaUrl,
        String contentType,
        long fileSize,
        Integer durationSec,
        int sortOrder,
        boolean enabled,
        Instant createdAt,
        /** Киоски, на которых крутится ролик. Пустой список = вся сеть. */
        List<String> kioskIds
) {}
