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

    @GetMapping("/playlist")
    public List<AdCreativeDto> playlist(@RequestParam("slot") AdSlot slot) {
        return adService.playlist(slot);
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
            @RequestParam(value = "sortOrder",   required = false) Integer sortOrder) {
        AdCreativeDto created = adService.upload(file, title, slot, durationSec, sortOrder);
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
