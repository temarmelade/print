package com.printkiosk.shared.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Уведомление об оплате от Bakai.
 *
 * <p>Формат подтверждён на реальном колбэке:
 * <pre>
 * {
 *   "accountNo": "1240040005729034",
 *   "amount": 5,
 *   "currencyID": 417,
 *   "operationID": "PIN-1724-01a0a5ef",
 *   "operationState": "success",
 *   "qrTransactionID": "OPENBANKING.888605",
 *   "elqrID": "e94d52c0-...",
 *   "createdDate": "2026-09-15T22:38:54.107"
 * }
 * </pre>
 *
 * <p>{@code @JsonIgnoreProperties} обязателен: банк может добавить поля,
 * и падать из-за этого на приёме денег недопустимо.
 *
 * @param operationID    наш orderId вида PIN-1724-01a0a5ef
 * @param operationState {@code success} при успешной оплате
 * @param amount         сумма — сверяется со стоимостью задания
 * @param qrTransactionID идентификатор операции в банке, для сверки
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BakaiCallbackPayload(
        String     accountNo,
        BigDecimal amount,
        Integer    currencyID,
        String     operationID,
        String     operationState,
        String     qrTransactionID,
        String     elqrID,
        String     createdDate
) {
    public boolean isSuccess() {
        return "success".equalsIgnoreCase(operationState);
    }
}
