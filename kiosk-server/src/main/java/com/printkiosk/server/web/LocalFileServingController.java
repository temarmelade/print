package com.printkiosk.server.web;

import com.printkiosk.server.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Локальный fallback для отдачи файлов. В prod-окружении эта
 * роль у Nginx, который читает тот же volume и отдаёт файлы напрямую.
 * Здесь только для удобства разработки.
 */
@Slf4j
@Profile("local")
@RestController
@RequiredArgsConstructor
public class LocalFileServingController {

    private final FileStorageService storage;

    @GetMapping("/files/{filename:.+}")
    public ResponseEntity<Resource> serve(@PathVariable("filename") String filename) {
        Path path;
        try {
            path = storage.resolve(filename);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid filename requested: {}", filename);
            return ResponseEntity.notFound().build();
        }

        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        MediaType contentType;
        try {
            String probed = Files.probeContentType(path);
            contentType = probed != null
                    ? MediaType.parseMediaType(probed)
                    : MediaType.APPLICATION_OCTET_STREAM;
        } catch (Exception e) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(new FileSystemResource(path));
    }
}