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

    @GetMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(
            @RequestParam("pin") @Pattern(regexp = "\\d{4}") String pin) {
        return ResponseEntity.ok(fileService.verify(pin));
    }

    @PostMapping("/{id}/consume")
    public ResponseEntity<Void> consume(@PathVariable UUID id) {
        return fileService.markConsumed(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).build();
    }
}