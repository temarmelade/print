package com.printkiosk.client.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printkiosk.client.config.ServerProperties;
import com.printkiosk.shared.api.dto.PaymentEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Простой SSE-клиент: открывает долгоживущее GET-соединение и парсит
 * стандартный text/event-stream-формат "event:NAME\ndata:JSON\n\n".
 * <p>
 * Spring WebClient умеет SSE из коробки, но он реактивный и тянет в наш
 * клиент Reactor. Здесь обходимся java.net.http.HttpClient — стандартный,
 * без лишних зависимостей.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStreamClient {

    private final ServerProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Closeable connect(String pin,
                             Consumer<PaymentEventDto> onEvent,
                             Consumer<Throwable> onError) {

        Thread worker = new Thread(() -> runStream(pin, onEvent, onError),
                "sse-payment-" + pin);
        worker.setDaemon(true);
        worker.start();

        return () -> {
            log.debug("Interrupting SSE worker for pin={}", maskPin(pin));
            worker.interrupt();
        };
    }

    private void runStream(String pin,
                           Consumer<PaymentEventDto> onEvent,
                           Consumer<Throwable> onError) {
        String url = properties.getBaseUrl() + "/api/payments/" + pin + "/stream";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        try {
            HttpResponse<java.io.InputStream> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                throw new IOException("SSE stream returned HTTP " + resp.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {

                String currentEvent = null;
                String currentData = null;
                String line;

                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        log.debug("SSE worker interrupted for pin={}", maskPin(pin));
                        return;
                    }

                    if (line.isBlank()) {
                        // Конец события — диспатчим
                        if (currentEvent != null && currentData != null) {
                            dispatch(currentEvent, currentData, onEvent);
                        }
                        currentEvent = null;
                        currentData  = null;
                        continue;
                    }
                    if (line.startsWith("event:")) currentEvent = line.substring(6).trim();
                    else if (line.startsWith("data:")) currentData  = line.substring(5).trim();
                    // id:, retry: и комментарии (:) — игнорируем
                }
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("SSE stream error for pin={}: {}", maskPin(pin), e.getMessage());
                onError.accept(e);
            }
        }
    }

    private void dispatch(String eventName, String data, Consumer<PaymentEventDto> onEvent) {
        // Игнорируем служебное "connected" — оно только для проверки канала
        if ("connected".equals(eventName)) {
            log.debug("SSE channel connected");
            return;
        }
        try {
            PaymentEventDto dto = objectMapper.readValue(data, PaymentEventDto.class);
            onEvent.accept(dto);
        } catch (Exception e) {
            log.warn("Failed to parse SSE event '{}': {}", eventName, e.getMessage());
        }
    }

    private static String maskPin(String pin) {
        return pin == null || pin.length() < 2 ? "****" : pin.substring(0, 2) + "**";
    }
}
