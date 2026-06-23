package com.printkiosk.server.web;

import com.printkiosk.server.service.print.PrintJobService;
import com.printkiosk.shared.api.dto.CreateJobRequest;
import com.printkiosk.shared.api.dto.JobPreviewRequest;
import com.printkiosk.shared.api.dto.JobPreviewResponse;
import com.printkiosk.shared.api.dto.JobResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobApiController {

    private final PrintJobService jobService;

    /** Создать job — киоск шлёт PIN + настройки печати. */
    @PostMapping
    public ResponseEntity<JobResponse> create(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(value = "X-Kiosk-Id", required = false) String kioskId) {

        JobResponse response = jobService.createJob(request, kioskId);
        return ResponseEntity
                .created(URI.create("/api/jobs/" + response.id()))
                .body(response);
    }

    /** Получить состояние job'а. */
    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(jobService.getJob(id));
    }

    /** Киоск начал физическую печать (PAID → PRINTING). */
    @PostMapping("/{id}/printing")
    public ResponseEntity<Void> startPrinting(@PathVariable UUID id) {
        return jobService.startPrinting(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();   // не в PAID
    }

    /** Печать успешно завершена (PRINTING → COMPLETED). */
    @PostMapping("/{id}/completed")
    public ResponseEntity<Void> markCompleted(@PathVariable UUID id) {
        return jobService.markCompleted(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }

    /** Аварийный fail — клиент сообщил об ошибке печати. */
    @PostMapping("/{id}/failed")
    public ResponseEntity<Void> markFailed(@PathVariable UUID id,
                                           @RequestBody(required = false) FailReason reason) {
        String r = reason != null ? reason.reason() : "unspecified";
        return jobService.markFailed(id, r)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }

    @PostMapping("/preview")
    public ResponseEntity<JobPreviewResponse> preview(
            @Valid @RequestBody JobPreviewRequest request,
            @RequestHeader(value = "X-Kiosk-Id", required = false) String kioskId) {

        return ResponseEntity.ok(jobService.previewJob(request, kioskId));
    }

    public record FailReason(String reason) {}
}