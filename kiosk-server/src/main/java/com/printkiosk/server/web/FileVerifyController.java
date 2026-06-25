package com.printkiosk.server.web;

import com.printkiosk.server.service.FileService;
import com.printkiosk.shared.api.dto.VerifyResponse;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Validated
public class FileVerifyController {

    private final FileService fileService;

    @PostMapping("/{id}/consume")
    public ResponseEntity<Void> consume(@PathVariable UUID id) {
        return fileService.markConsumed(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }

    @GetMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(
            @RequestParam("pin") @Pattern(regexp = "\\d{4}") String pin,
            @RequestHeader(value = "X-Kiosk-Id", required = false) String kioskId) {
        return ResponseEntity.ok(fileService.verify(pin, resolveKiosk(kioskId)));
    }

    @PostMapping("/{pin}/release")
    public ResponseEntity<Void> release(
            @PathVariable @Pattern(regexp = "\\d{4}") String pin,
            @RequestHeader(value = "X-Kiosk-Id", required = false) String kioskId) {
        fileService.releaseHold(pin, resolveKiosk(kioskId));
        return ResponseEntity.noContent().build();
    }

    private static String resolveKiosk(String kioskId) {
        return (kioskId == null || kioskId.isBlank()) ? "unknown-kiosk" : kioskId;
    }
}