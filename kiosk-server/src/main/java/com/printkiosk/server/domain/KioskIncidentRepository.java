package com.printkiosk.server.domain;

import com.printkiosk.shared.api.IncidentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KioskIncidentRepository extends JpaRepository<KioskIncidentEntity, Long> {

    /** Открытый инцидент конкретного типа — их не может быть больше одного (V13). */
    Optional<KioskIncidentEntity> findByKioskIdAndIncidentTypeAndResolvedAtIsNull(
            String kioskId, IncidentType incidentType);

    /** Все открытые инциденты киоска — нужно, чтобы закрыть исчезнувшие. */
    List<KioskIncidentEntity> findByKioskIdAndResolvedAtIsNull(String kioskId);

    /**
     * Лента открытых: сначала блокирующие. severity хранится строкой, и
     * 'DOWN' сортируется раньше 'WARNING' — то, что нужно.
     */
    @Query("""
            SELECT i FROM KioskIncidentEntity i
             WHERE i.resolvedAt IS NULL
             ORDER BY i.severity ASC, i.startedAt ASC
            """)
    List<KioskIncidentEntity> findOpenOrdered();

    /**
     * История за период. Намеренно два метода вместо одного с
     * {@code :kioskId IS NULL OR ...}: на Postgres несвязанный null-параметр
     * может не получить тип и уронить запрос в рантайме.
     */
    Page<KioskIncidentEntity> findByStartedAtGreaterThanEqualOrderByStartedAtDesc(
            Instant from, Pageable pageable);

    Page<KioskIncidentEntity> findByStartedAtGreaterThanEqualAndKioskIdOrderByStartedAtDesc(
            Instant from, String kioskId, Pageable pageable);
}
