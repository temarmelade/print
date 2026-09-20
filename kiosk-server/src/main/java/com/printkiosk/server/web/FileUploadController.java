package com.printkiosk.server.web;

import com.printkiosk.server.service.FileService;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.UploadResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Validated
public class FileUploadController {

    private final FileService fileService;

    /**
     * @param kiosk терминал, чей QR открыл страницу загрузки (параметр
     *              {@code k} из ссылки). Необязателен: старые ссылки и
     *              прямой заход на /upload продолжают работать. PIN при этом
     *              по-прежнему вводится на любом терминале — если «свой»
     *              занят, посетитель может подойти к соседнему.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file")    MultipartFile file,
            @RequestParam("source") @NotNull UploadSource source,
            @RequestParam(value = "telegramUserId", required = false) Long telegramUserId,
            @RequestParam(value = "kiosk", required = false) String kiosk
    ) throws IOException {

        // Сервис уже вернул готовый UploadResponse — пересобирать его
        // здесь значило бы терять поля при каждом расширении DTO.
        UploadResponse response = fileService.upload(file, source, telegramUserId);

        if (source == UploadSource.WEBSITE && kiosk != null
                && UploadPortalController.KIOSK_ID.matcher(kiosk).matches()) {
            log.info("Web upload pin={} via QR of kiosk={}", response.pin(), kiosk);
        }
        return ResponseEntity.ok(response);
    }
}
