package com.printkiosk.server.web;

import com.printkiosk.server.service.payment.PaymentEvent;
import com.printkiosk.server.service.payment.PaymentEventBus;
import com.printkiosk.shared.api.dto.PaymentEventDto;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;

/**
 * Server-Sent Events для уведомлений о смене статуса платежа.
 * <p>
 * Клиент подписывается на {@code /api/payments/{pin}/stream}, держит
 * соединение открытым, и получает события по мере их публикации
 * в {@link PaymentEventBus}.
 * <p>
 * При таймауте/разрыве клиент должен сам переподключиться.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Validated
public class PaymentStreamController {

    /** Таймаут SSE-соединения. Браузеры всё равно держат до 30s, мы ставим больше. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(6);

    private final PaymentEventBus eventBus;

    @GetMapping(value = "/{pin}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable @Pattern(regexp = "\\d{4}") String pin) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

        Runnable unsubscribe = eventBus.subscribe(pin, event -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.jobId().toString())
                        .name(event.type().name().toLowerCase())
                        .data(new PaymentEventDto(
                                event.pin(), event.jobId(),
                                event.type().name(), event.timestamp())));
            } catch (IOException e) {
                log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(() -> {
            log.debug("SSE timeout for pin={}", maskPin(pin));
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(e -> {
            log.debug("SSE error for pin={}: {}", maskPin(pin), e.getMessage());
            unsubscribe.run();
        });

        // Сразу шлём ping, чтобы клиент знал что соединение установлено.
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private static String maskPin(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }
}