package com.printkiosk.server.service.payment;

import com.printkiosk.shared.api.dto.PaymentSessionDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@Profile("mock")
@Primary
public class MockFinikPaymentGateway implements PaymentGateway {

    @Override
    public GatewayPaymentResult createPayment(String orderId, int amountSom) {
        String fakePaymentId = "MOCK-" + UUID.randomUUID();
        String fakeUrl = "http://localhost:8080/mock-payment/" + fakePaymentId;
        log.info("MOCK: created payment for orderId={} amount={} → paymentId={}",
                orderId, amountSom, fakePaymentId);
        return new GatewayPaymentResult(fakePaymentId, fakeUrl, amountSom, "PENDING");
    }
}