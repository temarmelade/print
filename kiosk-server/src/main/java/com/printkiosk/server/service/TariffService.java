package com.printkiosk.server.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.domain.KioskRepository;
import com.printkiosk.server.exception.AdminRuleViolationException;
import com.printkiosk.server.domain.TariffEntity;
import com.printkiosk.server.domain.TariffRepository;
import com.printkiosk.shared.api.dto.TariffDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение и изменение тарифов.
 *
 * <h2>Чтение</h2>
 * Возвращает действующий тариф для киоска:
 *  1. Ищет per-kiosk тариф (kiosk_id = ...).
 *  2. Если нет — возвращает глобальный дефолт (kiosk_id IS NULL).
 *  3. Если и дефолта нет — это операционная ошибка, бросаем исключение.
 *
 * <h2>Изменение</h2>
 * Цена никогда не переписывается на месте: старая строка закрывается
 * ({@code effective_to = now}), новая вставляется с {@code effective_from = now}.
 * Так у нас остаётся история — можно ответить на вопрос «по какой цене
 * человек печатал в прошлый вторник», и отчёты за прошлые периоды не
 * съезжают задним числом при смене прайса.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TariffService {

    private final TariffRepository repository;
    private final KioskRepository kioskRepository;

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

    // ══════════════════════════════════════════════════════════════════
    //  Админка
    // ══════════════════════════════════════════════════════════════════

    /** Все действующие цены: глобальная + переопределения по киоскам. */
    @Transactional(readOnly = true)
    public List<TariffDto> listCurrent() {
        Map<String, String> names = kioskNames();
        return repository.findAllCurrent().stream()
                .map(t -> toDto(t, names))
                .toList();
    }

    /** История изменений цены. {@code kioskId == null} — история дефолта. */
    @Transactional(readOnly = true)
    public List<TariffDto> history(String kioskId) {
        Map<String, String> names = kioskNames();
        return repository.findHistory(normalize(kioskId)).stream()
                .map(t -> toDto(t, names))
                .toList();
    }

    /**
     * Ставит новую цену. {@code kioskId == null} меняет глобальный дефолт,
     * иначе создаёт/обновляет переопределение конкретного киоска.
     */
    @Transactional
    public TariffDto setPrice(String kioskId, int bwPriceSom, int colorPriceSom) {
        String key = normalize(kioskId);

        if (bwPriceSom < 0 || colorPriceSom < 0) {
            throw new AdminRuleViolationException("Цена не может быть отрицательной");
        }
        if (key != null && !kioskRepository.existsById(key)) {
            throw new AdminRuleViolationException("Неизвестный киоск: " + key);
        }

        Instant now = Instant.now();
        Optional<TariffEntity> current = (key == null)
                ? repository.findByKioskIdIsNullAndEffectiveToIsNull()
                : repository.findByKioskIdAndEffectiveToIsNull(key);

        if (current.isPresent()) {
            TariffEntity active = current.get();
            if (active.getBwPriceSom() == bwPriceSom
                    && active.getColorPriceSom() == colorPriceSom) {
                // Цена не изменилась — не плодим строку в истории.
                return toDto(active, kioskNames());
            }
            active.setEffectiveTo(now);
            // Флаш обязателен. Уникальный частичный индекс из V4 разрешает
            // ровно одну открытую строку на киоск, а Hibernate по умолчанию
            // выполняет INSERT раньше UPDATE — без принудительного флаша
            // вставка новой строки упала бы на констрейнте.
            repository.saveAndFlush(active);
        }

        TariffEntity fresh = TariffEntity.builder()
                .id(UuidCreator.getTimeOrderedEpoch())
                .kioskId(key)
                .bwPriceSom(bwPriceSom)
                .colorPriceSom(colorPriceSom)
                .effectiveFrom(now)
                .effectiveTo(null)
                .createdAt(now)
                .build();
        repository.save(fresh);

        log.info("Tariff updated: kiosk={} bw={} color={}",
                key != null ? key : "<default>", bwPriceSom, colorPriceSom);
        return toDto(fresh, kioskNames());
    }

    /**
     * Убирает персональную цену киоска — он возвращается на глобальный
     * дефолт. Строка не удаляется, а закрывается: история остаётся.
     */
    @Transactional
    public void resetToDefault(String kioskId) {
        String key = normalize(kioskId);
        if (key == null) {
            throw new AdminRuleViolationException(
                    "Глобальный тариф нельзя сбросить — его можно только изменить");
        }
        repository.findByKioskIdAndEffectiveToIsNull(key).ifPresent(active -> {
            active.setEffectiveTo(Instant.now());
            repository.save(active);
            log.info("Tariff override removed for kiosk={}", key);
        });
    }

    private static String normalize(String kioskId) {
        return (kioskId == null || kioskId.isBlank()) ? null : kioskId;
    }

    private Map<String, String> kioskNames() {
        Map<String, String> map = new HashMap<>();
        kioskRepository.findAll().forEach(k -> map.put(k.getId(), k.getName()));
        return map;
    }

    private TariffDto toDto(TariffEntity e, Map<String, String> names) {
        return new TariffDto(
                e.getId(),
                e.getKioskId(),
                e.getKioskId() != null ? names.get(e.getKioskId()) : null,
                e.getBwPriceSom(),
                e.getColorPriceSom(),
                e.getEffectiveFrom(),
                e.getEffectiveTo()
        );
    }
}
