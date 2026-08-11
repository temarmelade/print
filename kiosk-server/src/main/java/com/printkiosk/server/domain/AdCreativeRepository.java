package com.printkiosk.server.domain;

import com.printkiosk.shared.api.AdSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AdCreativeRepository extends JpaRepository<AdCreative, UUID> {

    List<AdCreative> findBySlotAndEnabledTrueOrderBySortOrderAscCreatedAtAsc(AdSlot slot);

    List<AdCreative> findBySlotOrderBySortOrderAscCreatedAtAsc(AdSlot slot);

    /**
     * Плейлист для конкретного киоска: ролики без таргетинга (общие для всей
     * сети) плюс адресованные именно этому киоску.
     *
     * <p>Если kioskId пустой или неизвестный, MEMBER OF не совпадёт ни с чем
     * и останутся только общие ролики — безопасное поведение по умолчанию
     * для киоска, который ещё не зарегистрирован в сети.
     */
    @Query("""
           SELECT a FROM AdCreative a
            WHERE a.slot = :slot
              AND a.enabled = true
              AND (a.kioskIds IS EMPTY OR :kioskId MEMBER OF a.kioskIds)
            ORDER BY a.sortOrder ASC, a.createdAt ASC
           """)
    List<AdCreative> findPlaylistForKiosk(@Param("slot") AdSlot slot,
                                          @Param("kioskId") String kioskId);
}
