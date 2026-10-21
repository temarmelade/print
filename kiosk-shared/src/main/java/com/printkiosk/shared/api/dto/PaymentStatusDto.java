package com.printkiosk.shared.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Статус оплаты задания.
 *
 * <p>{@code ignoreUnknown}: клиент не падает от полей, которых не знает, —
 * сервер может обновиться раньше киоска.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentStatusDto(
        UUID jobId,
        String status,
        String paymentUrl
) {
    /**
     * Вычисляется из {@code status}, в JSON не передаётся. Без {@code @JsonIgnore}
     * Jackson записывал его как поле "paid", а прочитать обратно не мог:
     * у record нет такого компонента, и опрос оплаты на клиенте падал.
     */
    @JsonIgnore
    public boolean isPaid() {
        return "PAID".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status);
    }

    public static PaymentStatusDto notFound() {
        return new PaymentStatusDto(null, "NOT_FOUND", null);
    }
}