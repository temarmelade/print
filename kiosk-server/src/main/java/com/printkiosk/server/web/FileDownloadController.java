package com.printkiosk.server.web;

import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.exception.PinNotFoundException;
import com.printkiosk.server.service.FileService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Получение отсканированного документа на телефон по QR с киоска.
 * Ссылка открывается в обычном браузере посетителя, файл скачивается
 * напрямую (Content-Disposition: attachment).
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Validated
public class FileDownloadController {

    private final FileService fileService;

    /**
     * Скачивание по одноразовому токену из QR-ссылки.
     *
     * <p>Маршрут открыт без авторизации — его открывает браузер посетителя.
     * Отдаётся только скан с ОПЛАЧЕННОЙ доставкой (см.
     * {@link FileService#getPaidScanForDelivery}).
     *
     * <p>Отказ — это HTML-страница, а не JSON: её видит человек на телефоне,
     * и строка {@code {"code":"PIN_NOT_FOUND"}} ему ничего не объясняет.
     */
    @GetMapping("/d/{token}")
    public ResponseEntity<Resource> download(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,64}") String token) {

        FileEntity file;
        try {
            file = fileService.getPaidScanForDelivery(token);
        } catch (PinNotFoundException e) {
            return linkUnavailable();
        }

        Resource body = new FileSystemResource(fileService.storedPath(file));
        if (!body.exists()) {
            return linkUnavailable();
        }

        // attachment → браузер скачивает файл под оригинальным именем.
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(file.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                // Скан с личными данными не должен оседать в кэшах по пути.
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(body);
    }

    private static ResponseEntity<Resource> linkUnavailable() {
        String html = """
                <!DOCTYPE html>
                <html lang="ru"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Ссылка недоступна</title>
                <style>
                  body{font-family:-apple-system,"Segoe UI",Roboto,Arial,sans-serif;background:#F4F7FB;
                       color:#111827;display:flex;align-items:center;justify-content:center;
                       min-height:100vh;margin:0;padding:24px;box-sizing:border-box}
                  .card{background:#fff;border-radius:20px;padding:28px 24px;max-width:420px;
                        box-shadow:0 8px 24px rgba(17,24,39,.08);text-align:center}
                  h1{font-size:20px;margin:12px 0 8px} p{color:#6B7280;margin:6px 0;line-height:1.45}
                </style></head>
                <body><div class="card">
                  <div style="font-size:44px">⏳</div>
                  <h1>Ссылка больше не действует</h1>
                  <p>Документ хранится ограниченное время после оплаты. Отсканируйте его на терминале ещё раз.</p>
                  <p>Шилтеменин мөөнөтү бүттү. Документти терминалда кайра сканерлеңиз.</p>
                  <p>This link has expired. Please scan the document at the kiosk again.</p>
                </div></body></html>
                """;
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));
    }
}
