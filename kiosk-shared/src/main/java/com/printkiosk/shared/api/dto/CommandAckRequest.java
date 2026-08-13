package com.printkiosk.shared.api.dto;

import java.util.UUID;

/**
 * Подтверждение от киоска.
 *
 * @param accepted true — команда принята и выполняется; false — киоск
 *                 отказался (занят обслуживанием клиента)
 * @param message  причина отказа для админки
 */
public record CommandAckRequest(
        UUID commandId,
        boolean accepted,
        String message
) {}
