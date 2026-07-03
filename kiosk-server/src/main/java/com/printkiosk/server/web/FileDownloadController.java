package com.printkiosk.server.web;

import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.service.FileService;
import com.printkiosk.server.service.FileStorageService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Скачивание файла по PIN — веб-доставка отсканированных документов.
 * QR-код на киоске ведёт сюда: пользователь открывает ссылку с телефона и
 * файл скачивается напрямую (Content-Disposition: attachment).
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Validated
public class FileDownloadController {

    private final FileService fileService;
    private final FileStorageService storage;

    @GetMapping("/{pin}/download")
    public ResponseEntity<Resource> download(
            @PathVariable @Pattern(regexp = "\\d{4}") String pin) {

        FileEntity file = fileService.getForDownload(pin);
        Resource body = new FileSystemResource(storage.resolve(file.getStoredFilename()));
        if (!body.exists()) {
            return ResponseEntity.notFound().build();
        }

        // attachment → браузер скачивает файл под оригинальным именем.
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(file.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(body);
    }
}