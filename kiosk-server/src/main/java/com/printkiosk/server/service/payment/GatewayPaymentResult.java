package com.printkiosk.server.service.payment;

public record GatewayPaymentResult(
        String paymentId,
        String paymentUrl,
        int    amount,
        String status,
        /**
         * Готовая картинка QR от банка в base64 (PNG), если провайдер её
         * присылает. Рисовать свой код по ссылке ненадёжно: банковские
         * приложения ждут payload в своём формате, а не произвольный URL.
         * null — киоск сгенерирует QR из paymentUrl сам.
         */
        String qrImageBase64
) {
    public GatewayPaymentResult(String paymentId, String paymentUrl, int amount, String status) {
        this(paymentId, paymentUrl, amount, status, null);
    }
}