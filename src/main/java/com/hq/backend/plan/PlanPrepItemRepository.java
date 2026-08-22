package com.hq.backend.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPrepItemRepository extends JpaRepository<PlanPrepItem, UUID> {

    List<PlanPrepItem> findByPlanId(UUID planId);

    List<PlanPrepItem> findByPlanIdIn(List<UUID> planIds);

    Optional<PlanPrepItem> findByPlanPrepItemIdAndPlanId(UUID planPrepItemId, UUID planId);
}
