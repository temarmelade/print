package com.printkiosk.client.ui.flow;

public interface PaymentSessionListener {

    void onSessionLoading();

    void onSessionReady(String paymentUrl);

    void onSessionFailed(Throwable cause);

    void onPaymentSucceeded(String pin);
}
