package com.printkiosk.server.web;

import com.printkiosk.server.service.FileService;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.UploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Приём файлов с веб-портала. Намеренно тонкий: вся реальная работа
 * (валидация по magic-bytes, DOCX→PDF, сохранение в storage, генерация
 * PIN, запись в БД с retry) живёт в FileService.upload — ровно та же
 * логика, что и у Telegram-бота. Разница лишь в source = WEBSITE.
 */
@RestController
@RequestMapping("/api/web")
@RequiredArgsConstructor
public class WebUploadController {

    private final FileService fileService;

    /**
     * Принимает файл с сайта и возвращает PIN для ввода на киоске.
     *
     * Поле формы называется "file" (multipart/form-data).
     * telegramUserId здесь не нужен — передаём null.
     *
     * @return JSON UploadResponse { pin, expiresAt, ttlSeconds }
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file") MultipartFile file
    ) throws IOException {

        // Пустой файл отсечётся валидатором внутри upload(), но ранний
        // отказ дешевле — не гоняем magic-check по нулю байт.
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        UploadResponse result = fileService.upload(
                file,
                UploadSource.WEBSITE,   // ← единственное отличие от бота
                null                    // telegramUserId не применим для веба
        );
        return ResponseEntity.ok(result);
    }
}