package com.printkiosk.server.service.payment;

public interface PaymentGateway {
    GatewayPaymentResult createPayment(String orderId, int amountSom);
}
