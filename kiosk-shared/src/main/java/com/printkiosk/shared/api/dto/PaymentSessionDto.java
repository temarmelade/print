package com.printkiosk.shared.api.dto;

import java.util.UUID;

public record PaymentSessionDto(
        UUID    jobId,
        String  paymentId,
        String  paymentUrl,
        int     priceSom,
        /**
         * QR от банка в base64 (PNG). Если пусто, киоск рисует код сам
         * из {@link #paymentUrl}.
         */
        String  qrImageBase64
) {}
