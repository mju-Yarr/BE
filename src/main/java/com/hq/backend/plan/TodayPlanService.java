package com.hq.backend.plan;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.plan.dto.PlanDetailResponse;
import com.hq.backend.plan.dto.PlanResponse;
import com.hq.backend.plan.dto.RouteOptionResponse;
import com.hq.backend.plan.dto.TodayPlanResponse;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import com.hq.backend.wellness.DailySummaryService;
import com.hq.backend.wellness.dto.DailySummaryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TodayPlanService {
    private static final Set<String> EXCLUDED = Set.of("cancelled", "skipped");

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final RouteOptionRepository routeOptionRepository;
    private final PlanService planService;
    private final DailySummaryService dailySummaryService;

    @Transactional
    public TodayPlanResponse getToday(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        ZoneId zone = ZoneId.of(user.getTimezone());
        Instant now = Instant.now();
        LocalDate date = now.atZone(zone).toLocalDate();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Event> events = eventRepository.findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(userId, from, to)
                .stream().filter(event -> !EXCLUDED.contains(event.getStatus())).toList();

        Map<UUID, PlanRevision> revisionByEvent = events.isEmpty() ? Map.of()
                : planRevisionRepository.findByEventIdIn(events.stream().map(Event::getEventId).toList()).stream()
                        .filter(revision -> "active".equals(revision.getPlanStatus()))
                        .collect(Collectors.toMap(PlanRevision::getEventId, Function.identity(),
                                (left, right) -> left));
        List<PlanRevision> revisions = List.copyOf(revisionByEvent.values());
        Map<UUID, PlanDetailResponse> details = planService.getDetailsForEvents(revisions, events);
        Map<UUID, RouteOption> routes = routeOptionRepository.findAllById(revisions.stream()
                        .map(PlanRevision::getSelectedRouteOptionId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(RouteOption::getRouteOptionId, Function.identity()));

        List<TodayPlanResponse.Card> cards = new ArrayList<>();
        LinkedHashSet<String> degraded = new LinkedHashSet<>();
        for (Event event : events) {
            PlanRevision revision = revisionByEvent.get(event.getEventId());
            PlanDetailResponse detail = revision == null ? null : details.get(revision.getPlanId());
            if (detail != null) degraded.addAll(detail.degraded());
            RouteOption routeEntity = revision == null ? null : routes.get(revision.getSelectedRouteOptionId());
            RouteOptionResponse route = routeEntity == null ? null : RouteOptionResponse.from(routeEntity);
            EventResponse eventResponse = EventResponse.from(event, user.getTimezone(),
                    revision == null ? null : PlanResponse.from(revision), route);
            cards.add(new TodayPlanResponse.Card(cardState(event, detail, now), eventResponse, detail));
        }

        String homeState = resolveHomeState(cards, events, now);
        DailySummaryResponse wrap = "wrap".equals(homeState) ? loadWrapSummary(userId, date, degraded) : null;
        return new TodayPlanResponse(now, date, homeState, List.copyOf(cards), wrap, List.copyOf(degraded));
    }

    private String cardState(Event event, PlanDetailResponse plan, Instant now) {
        if ("arrived".equals(event.getStatus()) || "closed".equals(event.getStatus())) {
            boolean proposedWellness = plan != null && plan.wellnessActions().stream()
                    .anyMatch(action -> "proposed".equals(action.completionStatus()));
            return proposedWellness ? "wellness" : "wrap";
        }
        if (!now.isBefore(event.getStartsAt())) return "rush";
        if (plan == null) return "ease";
        if (!now.isBefore(plan.recommendedDepartAt())) return "depart";
        if (!now.isBefore(plan.prepStartAt())) return "start";
        return "ease";
    }

    private String resolveHomeState(List<TodayPlanResponse.Card> cards, List<Event> events, Instant now) {
        if (events.isEmpty()) return "ease";
        boolean dayFinished = events.stream().allMatch(event -> {
            Instant end = event.getEndsAt() == null ? event.getStartsAt().plusSeconds(3600) : event.getEndsAt();
            return end.isBefore(now) || "closed".equals(event.getStatus());
        });
        if (dayFinished && cards.stream().noneMatch(card -> "wellness".equals(card.state()))) return "wrap";
        return cards.stream().filter(card -> !"wrap".equals(card.state()))
                .map(TodayPlanResponse.Card::state).findFirst().orElse("wrap");
    }

    private DailySummaryResponse loadWrapSummary(UUID userId, LocalDate date, Set<String> degraded) {
        try {
            return dailySummaryService.getOrGenerate(userId, date);
        } catch (ApiException unavailable) {
            degraded.add("daily_summary_unavailable");
            return null;
        }
    }
}
