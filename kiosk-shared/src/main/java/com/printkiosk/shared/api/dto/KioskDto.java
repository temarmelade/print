package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.KioskHealth;
import com.printkiosk.shared.api.SupplySource;

import java.time.Instant;

/** Карточка киоска для админ-панели («Терминалы» + карта сети). */
public record KioskDto(
        String id,
        String name,
        String location,
        Double latitude,
        Double longitude,

        KioskHealth health,
        String healthReason,       // почему жёлтый/красный — человекочитаемо
        boolean online,
        boolean maintenanceMode,
        Instant lastSeenAt,

        Integer tonerPercent,
        SupplySource tonerSource,
        Integer paperPercent,
        SupplySource paperSource,
        Integer paperSheetsLeft,   // оценка в листах — техник мыслит листами

        boolean paperOut,
        boolean paperJam,
        boolean tonerLow,
        boolean tonerEmpty,
        boolean doorOpen,
        String printerError,

        Integer pageCounter,
        Instant paperRefilledAt,
        Instant cartridgeChangedAt
) {}
