package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
