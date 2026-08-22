package com.hq.backend.plan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteOptionRepository extends JpaRepository<RouteOption, UUID> {

    List<RouteOption> findByPlanIdOrderByRouteRankAsc(UUID planId);

    List<RouteOption> findByPlanIdIn(List<UUID> planIds);
}
