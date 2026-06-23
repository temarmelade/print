package com.printkiosk.server.service;

import com.printkiosk.server.domain.TariffEntity;
import com.printkiosk.server.domain.TariffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Возвращает действующий тариф для киоска. Алгоритм:
 *  1. Ищет per-kiosk тариф (kiosk_id = ...).
 *  2. Если нет — возвращает глобальный дефолт (kiosk_id IS NULL).
 *  3. Если и дефолта нет — это операционная ошибка, бросаем исключение.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TariffService {

    private final TariffRepository repository;

    @Transactional(readOnly = true)
    public TariffEntity getCurrentFor(String kioskId) {
        Instant now = Instant.now();

        if (kioskId != null && !kioskId.isBlank()) {
            var specific = repository.findCurrentForKiosk(kioskId, now);
            if (specific.isPresent()) return specific.get();
        }

        return repository.findCurrentDefault(now)
                .orElseThrow(() -> new IllegalStateException(
                        "No default tariff configured. Check tariffs table."));
    }
}
