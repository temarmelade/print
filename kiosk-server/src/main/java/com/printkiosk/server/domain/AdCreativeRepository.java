package com.printkiosk.server.domain;

import com.printkiosk.shared.api.AdSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdCreativeRepository extends JpaRepository<AdCreative, UUID> {

    List<AdCreative> findBySlotAndEnabledTrueOrderBySortOrderAscCreatedAtAsc(AdSlot slot);

    List<AdCreative> findBySlotOrderBySortOrderAscCreatedAtAsc(AdSlot slot);
}
