package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<TariffEntity, UUID> {

    /**
     * Действующий тариф для конкретного киоска на момент {@code at}.
     * Если для этого kioskId нет своей строки — возвращает Optional.empty(),
     * и вызывающий код должен пойти за {@link #findCurrentDefault(Instant)}.
     */
    @Query("""
           SELECT t FROM TariffEntity t
            WHERE t.kioskId = :kioskId
              AND t.effectiveFrom <= :at
              AND (t.effectiveTo IS NULL OR t.effectiveTo > :at)
           """)
    Optional<TariffEntity> findCurrentForKiosk(@Param("kioskId") String kioskId,
                                               @Param("at") Instant at);

    /** Глобальный дефолт (kiosk_id IS NULL). */
    @Query("""
           SELECT t FROM TariffEntity t
            WHERE t.kioskId IS NULL
              AND t.effectiveFrom <= :at
              AND (t.effectiveTo IS NULL OR t.effectiveTo > :at)
           """)
    Optional<TariffEntity> findCurrentDefault(@Param("at") Instant at);

    /**
     * Все действующие тарифы: глобальный дефолт + переопределения киосков.
     * Действующим считается тариф с открытым концом (effective_to IS NULL) —
     * ровно один на киоск, это гарантирует уникальный индекс из V4.
     */
    @Query("SELECT t FROM TariffEntity t WHERE t.effectiveTo IS NULL ORDER BY t.kioskId ASC NULLS FIRST")
    List<TariffEntity> findAllCurrent();

    /** Действующий тариф киоска без учёта времени — для правки в админке. */
    Optional<TariffEntity> findByKioskIdAndEffectiveToIsNull(String kioskId);

    /** Действующий глобальный дефолт. */
    Optional<TariffEntity> findByKioskIdIsNullAndEffectiveToIsNull();

    /** История цен киоска, свежие сверху. Для глобального дефолта kioskId = null. */
    @Query("""
           SELECT t FROM TariffEntity t
            WHERE (:kioskId IS NULL AND t.kioskId IS NULL)
               OR t.kioskId = :kioskId
            ORDER BY t.effectiveFrom DESC
           """)
    List<TariffEntity> findHistory(@Param("kioskId") String kioskId);
}
