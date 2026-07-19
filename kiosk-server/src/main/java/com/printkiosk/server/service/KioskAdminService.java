package com.printkiosk.server.service;

import com.printkiosk.server.domain.KioskEntity;
import com.printkiosk.server.domain.KioskRepository;
import com.printkiosk.server.exception.AdminRuleViolationException;
import com.printkiosk.server.security.KioskAuthService;
import com.printkiosk.server.web.AdminKioskController.CreateKioskRequest;
import com.printkiosk.server.web.AdminKioskController.CreatedKiosk;
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

    @Transactional
    public void delete(String id) {
        if (!kiosks.existsById(id)) {
            throw new AdminRuleViolationException("Киоск не найден");
        }
        kiosks.deleteById(id);
        log.warn("Kiosk {} удалён", id);
    }
}
