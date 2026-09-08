package com.printkiosk.server.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.config.KioskServerProperties;
import com.printkiosk.server.domain.AdCreative;
import com.printkiosk.server.domain.AdCreativeRepository;
import com.printkiosk.server.exception.AdNotFoundException;
import com.printkiosk.server.exception.FileValidationException;
import com.printkiosk.shared.api.AdMediaType;
import com.printkiosk.shared.api.AdSlot;
import com.printkiosk.shared.api.dto.AdCreativeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdService {

    private final AdCreativeRepository repository;
    private final FileStorageService storage;
    private final KioskServerProperties properties;
    private final com.printkiosk.server.domain.KioskRepository kioskRepository;
    private final GifToVideoConverter gifConverter;

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png",  "png",
            "image/gif",  "gif",
            "video/mp4",  "mp4",
            "video/webm", "webm"
    );

    /**
     * Плейлист киоска: общесетевые ролики + адресованные этому киоску.
     * kioskId приходит из заголовка X-Kiosk-Id, который клиент шлёт на
     * каждом запросе, — отдельного параметра на стороне киоска не нужно.
     */
    @Transactional(readOnly = true)
    public List<AdCreativeDto> playlist(AdSlot slot, String kioskId) {
        String key = (kioskId == null || kioskId.isBlank()) ? "" : kioskId;
        return repository.findPlaylistForKiosk(slot, key)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdCreativeDto> listForAdmin(AdSlot slot) {
        return repository
                .findBySlotOrderBySortOrderAscCreatedAtAsc(slot)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdCreative getOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(AdNotFoundException::new);
    }

    @Transactional
    public AdCreativeDto upload(MultipartFile file, String title, AdSlot slot,
                                Integer durationSec, Integer sortOrder,
                                List<String> kioskIds) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("Файл рекламы пуст");
        }

        String contentType = file.getContentType();
        String ext = ALLOWED_TYPES.get(contentType);
        if (ext == null) {
            throw new FileValidationException(
                    "Неподдерживаемый формат рекламы: " + contentType);
        }

        AdMediaType mediaType = contentType.startsWith("video/")
                ? AdMediaType.VIDEO
                : AdMediaType.IMAGE;

        Integer effectiveDuration = null;
        // GIF анимированный, длительность у него своя — требовать её от
        // оператора незачем. Если конвертация не сработает, показ пойдёт
        // по значению ниже.
        if (mediaType == AdMediaType.IMAGE && !"image/gif".equals(contentType)) {
            if (durationSec == null || durationSec <= 0) {
                throw new FileValidationException(
                        "Для картинки нужно указать длительность показа (сек)");
            }
            effectiveDuration = durationSec;
        } else if ("image/gif".equals(contentType)) {
            // Запасной вариант: ffmpeg недоступен, GIF останется картинкой.
            effectiveDuration = (durationSec != null && durationSec > 0) ? durationSec : 10;
        }

        UUID id = UuidCreator.getTimeOrderedEpoch();
        String storedName = "ad_" + id + "." + ext;

        try {
            storage.save(file, storedName);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось сохранить файл рекламы", e);
        }

        long storedSize = file.getSize();

        // GIF перегоняем в H.264. Формат сам по себе почти без сжатия, а его
        // декодер в JavaFX работает на потоке отрисовки — отсюда рывки на
        // мини-ПК. Конвертация одноразовая, здесь, а не на каждом киоске.
        if ("image/gif".equals(contentType)) {
            var converted = gifConverter.convert(storage.resolve(storedName));
            if (converted.isPresent()) {
                String mp4Name = "ad_" + id + ".mp4";
                storage.deleteQuietly(storedName);
                storedName = mp4Name;
                // Тип меняется на VIDEO: киоск проиграет ролик плеером,
                // а длительность возьмёт из самого файла.
                mediaType = AdMediaType.VIDEO;
                contentType = "video/mp4";
                effectiveDuration = null;
                try {
                    storedSize = java.nio.file.Files.size(converted.get());
                } catch (IOException ignored) {
                    // размер некритичен, останется исходный
                }
            }
        }

        AdCreative entity = AdCreative.builder()
                .id(id)
                .title(title != null && !title.isBlank() ? title : file.getOriginalFilename())
                .mediaType(mediaType)
                .slot(slot)
                .storedFilename(storedName)
                .originalFilename(file.getOriginalFilename())
                .contentType(contentType)
                .fileSize(storedSize)
                .durationSec(effectiveDuration)
                .sortOrder(sortOrder != null ? sortOrder : 0)
                .enabled(true)
                .createdAt(Instant.now())
                .kioskIds(validTargets(kioskIds))
                .build();

        repository.save(entity);
        log.info("Ad creative uploaded: id={} slot={} type={} title='{}' kiosks={}",
                id, slot, mediaType, entity.getTitle(),
                entity.getKioskIds().isEmpty() ? "<вся сеть>" : entity.getKioskIds());

        return toDto(entity);
    }

    @Transactional
    public AdCreativeDto setEnabled(UUID id, boolean enabled) {
        AdCreative entity = getOrThrow(id);
        entity.setEnabled(enabled);
        log.info("Ad creative {} enabled={}", id, enabled);
        return toDto(entity);
    }

    @Transactional
    public AdCreativeDto update(UUID id, String title, Integer sortOrder, Integer durationSec) {
        AdCreative entity = getOrThrow(id);
        if (title != null && !title.isBlank()) entity.setTitle(title);
        if (sortOrder != null)                 entity.setSortOrder(sortOrder);
        if (durationSec != null && entity.getMediaType() == AdMediaType.IMAGE) {
            entity.setDurationSec(durationSec);
        }
        return toDto(entity);
    }

    /**
     * Переназначает киоски показа. Пустой или null-список означает «крутить
     * везде» — это отдельный осмысленный режим, поэтому обнуление здесь
     * НЕ игнорируется, в отличие от null-полей в {@link #update}.
     */
    @Transactional
    public AdCreativeDto setTargets(UUID id, List<String> kioskIds) {
        AdCreative entity = getOrThrow(id);
        entity.getKioskIds().clear();
        entity.getKioskIds().addAll(validTargets(kioskIds));
        log.info("Ad creative {} targets = {}", id,
                entity.getKioskIds().isEmpty() ? "<вся сеть>" : entity.getKioskIds());
        return toDto(entity);
    }

    /**
     * Отсекает несуществующие киоски. Молча игнорировать их нельзя: иначе
     * оператор выберет киоск, тот окажется удалён, и ролик тихо уедет в
     * «показывать везде» — вместо ошибки получим рекламу не по адресу.
     */
    private Set<String> validTargets(List<String> kioskIds) {
        if (kioskIds == null || kioskIds.isEmpty()) return new LinkedHashSet<>();
        Set<String> cleaned = new LinkedHashSet<>();
        for (String raw : kioskIds) {
            if (raw == null || raw.isBlank()) continue;
            String kioskId = raw.trim();
            if (!kioskRepository.existsById(kioskId)) {
                throw new FileValidationException("Неизвестный киоск: " + kioskId);
            }
            cleaned.add(kioskId);
        }
        return cleaned;
    }

    @Transactional
    public void delete(UUID id) {
        AdCreative entity = getOrThrow(id);
        repository.delete(entity);
        storage.deleteQuietly(entity.getStoredFilename());
        log.info("Ad creative deleted: id={}", id);
    }

    private AdCreativeDto toDto(AdCreative e) {
        return new AdCreativeDto(
                e.getId(),
                e.getTitle(),
                e.getMediaType(),
                e.getSlot(),
                buildMediaUrl(e.getId()),
                e.getContentType(),
                e.getFileSize(),
                e.getDurationSec(),
                e.getSortOrder(),
                e.isEnabled(),
                e.getCreatedAt(),
                List.copyOf(e.getKioskIds())
        );
    }

    private String buildMediaUrl(UUID id) {
        String base = properties.getStorage().getPublicBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/api/ads/media/" + id;
    }
}