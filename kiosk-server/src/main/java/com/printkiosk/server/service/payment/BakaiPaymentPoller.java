package com.printkiosk.server.service.payment;

import com.printkiosk.server.config.BakaiProperties;
import com.printkiosk.server.domain.PrintJobEntity;
import com.printkiosk.server.domain.PrintJobRepository;
import com.printkiosk.server.service.print.PrintJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Опрашивает Bakai о статусе неоплаченных заданий.
 *
 * <p>Существует только потому, что у Bakai нет вебхуков: в спецификации
 * OpenBanking API нет ни одного эндпоинта обратного вызова, статус можно
 * узнать исключительно запросом {@code GetStateCustomQr}. У Finik было
 * наоборот — банк сам стучался к нам.
 *
 * <p>Для киоска разницы нет: подтверждение публикуется в тот же
 * {@link PaymentEventBus}, откуда уходит на терминал по SSE.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "bakai", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class BakaiPaymentPoller {

    private final BakaiProperties props;
    private final BakaiPaymentGateway gateway;
    private final PrintJobRepository jobs;
    private final PrintJobService jobService;
    private final PaymentEventBus eventBus;

    @Scheduled(fixedDelayString = "${bakai.poll-interval-ms:3000}")
    @Transactional
    public void poll() {
        Instant notOlderThan = Instant.now().minusSeconds(props.getPollTimeoutMin() * 60);
        List<PrintJobEntity> pending = jobs.findAwaitingPayment(notOlderThan);
        if (pending.isEmpty()) return;

        for (PrintJobEntity job : pending) {
            String orderId = job.getPaymentId();
            String state = gateway.fetchState(orderId);

            // null = связи не было. Не трогаем задание: объявить платёж
            // неуспешным из-за сетевого сбоя хуже, чем подождать.
            if (state == null || "Processed".equalsIgnoreCase(state)) continue;

            // Разбор один на оба пути оплаты — иначе форматы разъедутся
            // при следующей правке orderId.
            String pin = PaymentService.extractPin(orderId);
            if (pin == null) {
                log.warn("Не смог разобрать PIN из paymentId={}", orderId);
                continue;
            }

            if ("Success".equalsIgnoreCase(state)) {
                if (jobService.applyPaidByPin(pin)) {
                    publish(pin, PaymentEvent.Type.PAID);
                    log.info("Оплата подтверждена опросом Bakai: pin={}", mask(pin));
                }
            } else if ("Error".equalsIgnoreCase(state)) {
                jobService.failByPin(pin);
                publish(pin, PaymentEvent.Type.FAILED);
                log.info("Оплата отклонена банком: pin={}", mask(pin));
            } else {
                log.warn("Неизвестный статус Bakai '{}' для {}", state, orderId);
            }
        }
    }

    private void publish(String pin, PaymentEvent.Type type) {
        jobs.findLatestActiveByPin(pin, Instant.now()).ifPresent(job ->
                eventBus.publish(new PaymentEvent(pin, job.getId(), type, Instant.now())));
    }

    private static String mask(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }
}