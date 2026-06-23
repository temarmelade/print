package com.printkiosk.server.web;

import com.printkiosk.server.service.FileService;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.UploadResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Validated
public class FileUploadController {

    private final FileService fileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file")    MultipartFile file,
            @RequestParam("source") @NotNull UploadSource source,
            @RequestParam(value = "telegramUserId", required = false) Long telegramUserId
    ) throws IOException {

        var result = fileService.upload(file, source, telegramUserId);
        return ResponseEntity.ok(new UploadResponse(
                result.pin(),
                result.expiresAt(),
                result.ttlSeconds()
        ));
    }
}
