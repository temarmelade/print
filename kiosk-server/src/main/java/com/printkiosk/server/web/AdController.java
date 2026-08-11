package com.printkiosk.server.web;

import com.printkiosk.server.domain.AdCreative;
import com.printkiosk.server.service.AdService;
import com.printkiosk.server.service.FileStorageService;
import com.printkiosk.shared.api.AdSlot;
import com.printkiosk.shared.api.dto.AdCreativeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ads")
@RequiredArgsConstructor
public class AdController {

    private final AdService adService;
    private final FileStorageService storage;

    /**
     * Плейлист киоска. Киоск не передаёт свой id явно — он уже едет в
     * заголовке X-Kiosk-Id на каждом запросе (см. HttpClientConfig клиента),
     * поэтому таргетинг заработал без единой правки на стороне киоска.
     */
    @GetMapping("/playlist")
    public List<AdCreativeDto> playlist(
            @RequestParam("slot") AdSlot slot,
            @RequestHeader(value = "X-Kiosk-Id", required = false) String kioskId) {
        return adService.playlist(slot, kioskId);
    }

    @GetMapping("/media/{id}")
    public ResponseEntity<Resource> media(@PathVariable UUID id) {
        AdCreative ad = adService.getOrThrow(id);
        Resource body = new FileSystemResource(storage.resolve(ad.getStoredFilename()));
        if (!body.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Accept-Ranges", "bytes")
                .contentType(MediaType.parseMediaType(ad.getContentType()))
                .body(body);
    }

    @GetMapping("/admin")
    public List<AdCreativeDto> listForAdmin(@RequestParam("slot") AdSlot slot) {
        return adService.listForAdmin(slot);
    }

    @PostMapping(value = "/admin", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AdCreativeDto> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("slot") AdSlot slot,
            @RequestParam(value = "title",       required = false) String title,
            @RequestParam(value = "durationSec", required = false) Integer durationSec,
            @RequestParam(value = "sortOrder",   required = false) Integer sortOrder,
            @RequestParam(value = "kioskIds",    required = false) List<String> kioskIds) {
        AdCreativeDto created =
                adService.upload(file, title, slot, durationSec, sortOrder, kioskIds);
        return ResponseEntity.ok(created);
    }

    @PatchMapping("/admin/{id}")
    public AdCreativeDto update(
            @PathVariable UUID id,
            @RequestParam(value = "title",       required = false) String title,
            @RequestParam(value = "sortOrder",   required = false) Integer sortOrder,
            @RequestParam(value = "durationSec", required = false) Integer durationSec) {
        return adService.update(id, title, sortOrder, durationSec);
    }

    /**
     * Список киосков показа. Пустой список = крутить на всей сети, поэтому
     * параметр обязателен: отсутствие значения и пустое значение здесь
     * означают разное.
     */
    @PutMapping("/admin/{id}/targets")
    public AdCreativeDto setTargets(@PathVariable UUID id,
                                    @RequestBody List<String> kioskIds) {
        return adService.setTargets(id, kioskIds);
    }

    @PatchMapping("/admin/{id}/enabled")
    public AdCreativeDto setEnabled(
            @PathVariable UUID id,
            @RequestParam("enabled") boolean enabled) {
        return adService.setEnabled(id, enabled);
    }

    @DeleteMapping("/admin/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        adService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
