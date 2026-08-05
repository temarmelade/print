package com.printkiosk.server.service;

import com.printkiosk.server.domain.KioskEntity;
import com.printkiosk.server.domain.KioskRepository;
import com.printkiosk.server.exception.AdminRuleViolationException;
import com.printkiosk.server.security.KioskAuthService;
import com.printkiosk.server.web.AdminKioskController.CreateKioskRequest;
import com.printkiosk.server.web.AdminKioskController.CreatedKiosk;
import com.printkiosk.server.web.AdminKioskController.UpdateKioskRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Регистрация киосков и выдача им API-ключей.
 *
 * <p>Ключ показывается ровно один раз — в базе лежит только bcrypt-хеш.
 * Если ключ потеряли, его не «посмотреть», а только перевыпустить.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KioskAdminService {

    /** Значения по умолчанию — под Canon MF232w. */
    private static final int DEFAULT_PAPER_CAPACITY = 250;   // кассета
    private static final int DEFAULT_CARTRIDGE_YIELD = 2400; // картридж 737

    private final KioskRepository kiosks;
    private final KioskAuthService auth;

    @Transactional
    public CreatedKiosk create(CreateKioskRequest req) {
        if (kiosks.existsById(req.id())) {
            throw new AdminRuleViolationException("Киоск с таким ID уже зарегистрирован");
        }

        String apiKey = auth.generateApiKey();

        KioskEntity k = KioskEntity.builder()
                .id(req.id().trim())
                .name(req.name().trim())
                .location(req.location())
                .latitude(req.latitude())
                .longitude(req.longitude())
                .apiKeyHash(auth.hash(apiKey))
                .paperCapacity(req.paperCapacity() != null ? req.paperCapacity() : DEFAULT_PAPER_CAPACITY)
                .cartridgeYield(req.cartridgeYield() != null ? req.cartridgeYield() : DEFAULT_CARTRIDGE_YIELD)
                .maintenanceMode(false)
                .enabled(true)
                .createdAt(Instant.now())
                .build();

        kiosks.save(k);
        log.info("Kiosk registered: id={} name={}", k.getId(), k.getName());

        return new CreatedKiosk(k.getId(), k.getName(), apiKey);
    }

    @Transactional
    public CreatedKiosk rotateKey(String id) {
        KioskEntity k = kiosks.findById(id)
                .orElseThrow(() -> new AdminRuleViolationException("Киоск не найден"));

        String apiKey = auth.generateApiKey();
        k.setApiKeyHash(auth.hash(apiKey));
        log.warn("Kiosk {}: API-ключ перевыпущен", id);

        return new CreatedKiosk(k.getId(), k.getName(), apiKey);
    }

    /**
     * Редактирование киоска. ID и ключ не трогаем: ID — это идентификатор,
     * под которым терминал уже настроен, а ключ меняется только через
     * перевыпуск. Незаданные (null) поля оставляем как есть — форма может
     * прислать только изменённое.
     */
    @Transactional
    public void update(String id, UpdateKioskRequest req) {
        KioskEntity k = kiosks.findById(id)
                .orElseThrow(() -> new AdminRuleViolationException("Киоск не найден"));

        if (req.name() != null && !req.name().isBlank()) k.setName(req.name().trim());
        if (req.location() != null)  k.setLocation(blankToNull(req.location()));
        if (req.latitude() != null)  k.setLatitude(req.latitude());
        if (req.longitude() != null) k.setLongitude(req.longitude());
        if (req.paperCapacity() != null && req.paperCapacity() > 0) {
            k.setPaperCapacity(req.paperCapacity());
        }
        if (req.cartridgeYield() != null && req.cartridgeYield() > 0) {
            k.setCartridgeYield(req.cartridgeYield());
        }

        log.info("Kiosk {} обновлён", id);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @Transactional
    public void delete(String id) {
        if (!kiosks.existsById(id)) {
            throw new AdminRuleViolationException("Киоск не найден");
        }
        kiosks.deleteById(id);
        log.warn("Kiosk {} удалён", id);
    }
}
