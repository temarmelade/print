package com.printkiosk.server.domain;

import com.printkiosk.shared.api.KioskCommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KioskCommandRepository extends JpaRepository<KioskCommandEntity, UUID> {

    /** Ожидающая команда киоска. Их не может быть больше одной (см. V16). */
    Optional<KioskCommandEntity> findByKioskIdAndStatus(String kioskId, KioskCommandStatus status);

    /** Последняя команда — для колонки состояния в списке терминалов. */
    Optional<KioskCommandEntity> findFirstByKioskIdOrderByCreatedAtDesc(String kioskId);

    List<KioskCommandEntity> findTop50ByKioskIdOrderByCreatedAtDesc(String kioskId);

    /** Незабранные и неподтверждённые команды старше порога — кандидаты на протухание. */
    @Query("""
           SELECT c FROM KioskCommandEntity c
            WHERE c.status IN (:statuses)
              AND c.createdAt < :threshold
           """)
    List<KioskCommandEntity> findStale(@Param("statuses") List<KioskCommandStatus> statuses,
                                       @Param("threshold") Instant threshold);
}
