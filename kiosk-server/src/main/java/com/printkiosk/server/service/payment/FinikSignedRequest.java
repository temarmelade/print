package com.printkiosk.server.service.payment;

import java.util.Map;

/**
 * Чистое представление подписанного запроса: всё, что нужно для построения
 * canonical payload, без зависимости от сервлет-контейнера.
 * <p>
 * Это позволяет тестировать {@link FinikWebhookVerifier} без MockMvc/мок-запросов.
 */
public record FinikSignedRequest(
        String              method,
        String              path,
        String              host,
        Map<String, String> apiHeaders,    // только x-api-*
        String              queryString,   // raw, как пришёл (может быть null)
        String              rawBody,
        String              signatureB64,
        Long                timestampMs
) {}