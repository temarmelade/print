package com.printkiosk.client.api;

import com.printkiosk.shared.api.dto.*;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.AdSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.Closeable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Единая точка обращения JavaFX-клиента к серверу.
 * <p>
 * Все методы синхронные. Вызывающий код должен запускать их в
 * фоновом потоке (например, через {@link javafx.concurrent.Task}),
 * иначе на UI-потоке заблокирует анимацию во время сетевых запросов.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KioskServerClient {

    private final RestClient http;
    private final PaymentStreamClient  paymentStreamClient;
    // ════════════════════════════════════════════════════════════════
    //  Files
    // ════════════════════════════════════════════════════════════════

    public VerifyResponse verify(String pin) {
        return execute(() -> http.get()
                .uri(uri -> uri.path("/api/files/verify").queryParam("pin", pin).build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    int status = res.getStatusCode().value();
                    if (status == 404) {
                        throw new PinNotFoundException();
                    }
                    if (status == 423) {
                        throw new PinLockedException();
                    }
                    throw new KioskServerException("CLIENT_ERROR",
                            "Ошибка запроса: " + res.getStatusCode());
                })
                .body(VerifyResponse.class));
    }

    public void consumeFile(UUID fileId) {
        execute(() -> http.post()
                .uri("/api/files/{id}/consume", fileId)
                .retrieve()
                .toBodilessEntity());
    }

    // ════════════════════════════════════════════════════════════════
    //  Jobs
    // ════════════════════════════════════════════════════════════════

    public JobResponse createJob(CreateJobRequest request) {
        return execute(() -> http.post()
                .uri("/api/jobs")
                .body(request)
                .retrieve()
                .body(JobResponse.class));
    }

    public JobResponse getJob(UUID jobId) {
        return execute(() -> http.get()
                .uri("/api/jobs/{id}", jobId)
                .retrieve()
                .body(JobResponse.class));
    }

    public void startPrinting(UUID jobId)  { transitionJob(jobId, "printing");  }
    public void markCompleted(UUID jobId)  { transitionJob(jobId, "completed"); }
    public void markFailed(UUID jobId)     { transitionJob(jobId, "failed");    }

    private void transitionJob(UUID jobId, String action) {
        execute(() -> http.post()
                .uri("/api/jobs/{id}/{action}", jobId, action)
                .retrieve()
                .toBodilessEntity());
    }

    // ════════════════════════════════════════════════════════════════
    //  Payments
    // ════════════════════════════════════════════════════════════════

    public PaymentSessionDto createPayment(UUID jobId) {
        return execute(() -> http.post()
                .uri("/api/payments")
                .body(new CreatePaymentRequest(jobId))
                .retrieve()
                .body(PaymentSessionDto.class));
    }

    public PaymentStatusDto getPaymentStatus(String pin) {
        return execute(() -> http.get()
                .uri("/api/payments/{pin}/status", pin)
                .retrieve()
                .body(PaymentStatusDto.class));
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Унифицированная обёртка: ResourceAccessException (таймаут/сеть)
     * превращается в ServerUnavailableException, чтобы UI не разбирал
     * сетевые низкоуровневые причины.
     */
    private <T> T execute(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (KioskServerException e) {
            throw e;                       // уже наше типизированное
        } catch (ResourceAccessException e) {
            log.warn("Network/timeout error talking to server", e);
            throw new ServerUnavailableException(e);
        } catch (Exception e) {
            log.error("Unexpected error talking to server", e);
            throw new KioskServerException("UNKNOWN", e.getMessage(), e);
        }
    }

    /** Внутренний record для тела запроса. В shared не выносим — это внутреннее. */
    private record CreatePaymentRequest(UUID jobId) {}

    public JobPreviewResponse previewJob(JobPreviewRequest request) {
        return execute(() -> http.post()
                .uri("/api/jobs/preview")
                .body(request)
                .retrieve()
                .body(JobPreviewResponse.class));
    }

    // ════════════════════════════════════════════════════════════════
    //  Ads
    // ════════════════════════════════════════════════════════════════

    /** Активный плейлист рекламы для слота (например, HOME для заставки). */
    public List<AdCreativeDto> adPlaylist(AdSlot slot) {
        return execute(() -> http.get()
                .uri(uri -> uri.path("/api/ads/playlist").queryParam("slot", slot).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<AdCreativeDto>>() {}));
    }

    // ════════════════════════════════════════════════════════════════
    //  Upload (скан/ксерокопия → серверный файл + PIN)
    // ════════════════════════════════════════════════════════════════

    /**
     * Загружает локальный файл на сервер и возвращает PIN. Используется
     * ксерокопией: собранный из сканов PDF заливается как обычный файл
     * печати, после чего работает весь стандартный тракт (настройки/оплата/
     * печать) по этому PIN.
     */
    public UploadResponse uploadFile(java.io.File file, UploadSource source) {
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", new org.springframework.core.io.FileSystemResource(file));
        body.add("source", source.name());
        return execute(() -> http.post()
                .uri("/api/files/upload")
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(UploadResponse.class));
    }
}