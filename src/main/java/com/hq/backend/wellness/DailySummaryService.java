package com.hq.backend.wellness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventStatus;
import com.hq.backend.metrics.ProductEvent;
import com.hq.backend.metrics.ProductEventRepository;
import com.hq.backend.notification.NotificationRepository;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.plan.PlanContext;
import com.hq.backend.plan.PlanContextRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.hq.backend.wellness.dto.DailySummaryEngineRequest;
import com.hq.backend.wellness.dto.DailySummaryEngineResponse;
import com.hq.backend.wellness.dto.DailySummaryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// API 명세 §12.4 — DWL = 0.6*avgWisWeighted + 0.4*avgRls(TRD).
// DWL은 건강 점수가 아닌 하루의 환경·이동 부담을 요약하는 내부 지표다. 한 번 생성되면
// 그대로 캐시한다(GET 재호출로 재계산하지 않음) — isViewed를 덮어쓰지 않기 위함이다.
// 관리 일정 0건이면 카드를 만들지 않는다(404, 숫자를 지어내지 않는다).
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 주간 요약(WeeklySummaryService)과 같은 정의를 쓴다. 두 화면이 같은 날의
    // "정시 도착"을 다르게 세면 안 된다.
    private static final String ARRIVAL_ON_TIME = "on_time";
    private static final String ARRIVAL_UNKNOWN = "unknown";

    private final EventRepository eventRepository;
    private final PlanRevisionRepository planRevisionRepository;
    private final EventExecutionRepository eventExecutionRepository;
    private final PlanWellnessScoreRepository planWellnessScoreRepository;
    private final PlanContextRepository planContextRepository;
    private final DailyWellnessSummaryRepository dailyWellnessSummaryRepository;
    private final ProductEventRepository productEventRepository;
    private final NotificationRepository notificationRepository;
    private final WellnessEngineClient wellnessEngineClient;
    private final com.hq.backend.config.WellnessConfigService wellnessConfigService;
    private final ObjectMapper objectMapper;

    @Transactional
    public DailySummaryResponse getOrGenerate(UUID userId, LocalDate date) {
        return dailyWellnessSummaryRepository.findByUserIdAndSummaryDate(userId, date)
                .map(DailySummaryResponse::from)
                .orElseGet(() -> DailySummaryResponse.from(generate(userId, date)));
    }

    /** Scheduler entry point. Existing rows are preserved, including the viewed state. */
    @Transactional
    public void generateIfAbsent(UUID userId, LocalDate date) {
        if (dailyWellnessSummaryRepository.findByUserIdAndSummaryDate(userId, date).isEmpty()) {
            generate(userId, date);
        }
    }

    // API 명세 §12.4 — "조회 기록 (지표)". PRODUCT_EVENT는 좌표·제목·민감 항목명을
    // payload에 절대 넣지 않는다(절대 원칙 8) — summaryId/summaryDate만 담는다.
    @Transactional
    public DailySummaryResponse markViewed(UUID userId, UUID summaryId) {
        DailyWellnessSummary summary = dailyWellnessSummaryRepository.findBySummaryIdAndUserId(summaryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUMMARY_NOT_FOUND", "일일 요약을 찾을 수 없습니다."));
        summary.setViewed(true);

        Instant now = Instant.now();
        productEventRepository.save(ProductEvent.builder()
                .userId(userId)
                .eventName("card_viewed")
                .occurredAt(now)
                .receivedAt(now)
                .payload(toJson(Map.of("summaryId", summaryId.toString(), "summaryDate", summary.getSummaryDate().toString())))
                .build());

        return DailySummaryResponse.from(summary);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private DailyWellnessSummary generate(UUID userId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<Event> managedEvents = eventRepository
                .findByUserIdAndStartsAtBetweenOrderByStartsAtAsc(userId, dayStart, dayEnd).stream()
                .filter(event -> isManagedStatus(event.getStatus()))
                .toList();
        if (managedEvents.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUMMARY_NOT_GENERATED", "해당 날짜에 관리한 일정이 없습니다.");
        }

        List<UUID> eventIds = managedEvents.stream().map(Event::getEventId).toList();
        Map<UUID, EventExecution> executionByEvent = eventExecutionRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(EventExecution::getEventId, Function.identity()));

        // 모든 계획 리비전을 한 번에 읽은 뒤 이벤트별 최고 revisionNo만 선택한다. 이후 aggregate는
        // 이 map만 참조하므로 이벤트 수에 비례해 PlanRevision 쿼리가 늘어나지 않는다.
        Map<UUID, PlanRevision> latestPlanByEvent = planRevisionRepository.findByEventIdIn(eventIds).stream()
                .collect(Collectors.groupingBy(
                        PlanRevision::getEventId,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparingInt(PlanRevision::getRevisionNo)),
                                Optional::orElseThrow)));
        List<UUID> planIds = latestPlanByEvent.values().stream().map(PlanRevision::getPlanId).toList();
        Map<UUID, PlanWellnessScore> scoreByPlan = planWellnessScoreRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(PlanWellnessScore::getPlanId, Function.identity()));
        Map<UUID, PlanContext> contextByPlan = planContextRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(PlanContext::getPlanId, Function.identity()));

        Aggregate agg = aggregate(managedEvents, executionByEvent, latestPlanByEvent, scoreByPlan, contextByPlan);
        String localScenario = pickScenario(managedEvents, executionByEvent, agg);
        DailySummaryEngineResponse engine = requestDailySummary(date, managedEvents, executionByEvent,
                latestPlanByEvent, scoreByPlan, contextByPlan);
        String scenario = engine == null || engine.cardScenario() == null ? localScenario : engine.cardScenario();

        return dailyWellnessSummaryRepository.save(DailyWellnessSummary.builder()
                .userId(userId)
                .summaryDate(date)
                .eventCount(engine == null ? managedEvents.size() : engine.eventCount())
                .totalOutdoorMinutes(engine == null || engine.totalOutdoorMinutes() == null
                        ? agg.totalOutdoorMinutes : engine.totalOutdoorMinutes())
                .outdoorSource(agg.allObserved() ? "observed" : "estimated")
                .onTimeCount(agg.onTimeCount())
                .arrivalSampleCount(agg.arrivalSampleCount())
                .avgWisWeighted(engine == null || engine.avgWisWeighted() == null
                        ? agg.avgWisWeighted : BigDecimal.valueOf(engine.avgWisWeighted()))
                .avgRls(engine == null || engine.avgRls() == null
                        ? agg.avgRls : BigDecimal.valueOf(engine.avgRls()))
                .dwlScore(engine == null || engine.dwlScore() == null
                        ? agg.dwlScore() : Short.valueOf(engine.dwlScore().shortValue()))
                .dwlBand(engine == null || engine.dwlBand() == null ? agg.dwlBand : engine.dwlBand())
                .cardScenario(scenario)
                .cardMessageSnapshot(engine == null || engine.cardMessage() == null ? messageFor(scenario) : engine.cardMessage())
                .createdAt(Instant.now())
                .build());
    }

    private DailySummaryEngineResponse requestDailySummary(
            LocalDate date, List<Event> events, Map<UUID, EventExecution> executionByEvent,
            Map<UUID, PlanRevision> latestPlanByEvent, Map<UUID, PlanWellnessScore> scoreByPlan,
            Map<UUID, PlanContext> contextByPlan) {
        List<DailySummaryEngineRequest.EventSummary> summaries = events.stream().map(event -> {
            PlanRevision plan = latestPlanByEvent.get(event.getEventId());
            EventExecution execution = executionByEvent.get(event.getEventId());
            PlanWellnessScore score = plan == null ? null : scoreByPlan.get(plan.getPlanId());
            PlanContext context = plan == null ? null : contextByPlan.get(plan.getPlanId());
            Integer outdoor = execution != null && execution.getActualOutdoorMinutes() != null
                    ? execution.getActualOutdoorMinutes() : context == null ? null : context.getEstimatedOutdoorMinutes();
            return new DailySummaryEngineRequest.EventSummary(event.getEventId().toString(),
                    score == null ? null : (int) score.getWisScore(),
                    execution == null || execution.getRushLoadScore() == null ? null : (int) execution.getRushLoadScore(),
                    outdoor, execution != null && execution.getActualOutdoorMinutes() != null);
        }).toList();
        int criticalAlertCount = latestPlanByEvent.isEmpty() ? 0
                : notificationRepository.countSentCriticalByPlanIdInBetween(
                        latestPlanByEvent.values().stream().map(PlanRevision::getPlanId).toList(),
                        date.atStartOfDay(ZONE).toInstant(), date.plusDays(1).atStartOfDay(ZONE).toInstant());
        return wellnessEngineClient.summarizeDay(new DailySummaryEngineRequest(date, summaries, 0, 0,
                criticalAlertCount, wellnessConfigService.current())).orElse(null);
    }

    private record Aggregate(
            int totalOutdoorMinutes,
            boolean allObserved,
            int onTimeCount,
            int arrivalSampleCount,
            BigDecimal avgWisWeighted,
            BigDecimal avgRls,
            Short dwlScore,
            String dwlBand) {
    }

    private Aggregate aggregate(
            List<Event> events,
            Map<UUID, EventExecution> executionByEvent,
            Map<UUID, PlanRevision> latestPlanByEvent,
            Map<UUID, PlanWellnessScore> scoreByPlan,
            Map<UUID, PlanContext> contextByPlan) {
        int totalOutdoor = 0;
        boolean hasOutdoorDataPoint = false;
        boolean anyEstimated = false;
        int onTimeCount = 0;
        int arrivalSampleCount = 0;
        double wisWeightedSum = 0;
        int wisWeightTotal = 0;
        double rlsSum = 0;
        int rlsCount = 0;

        for (Event event : events) {
            EventExecution execution = executionByEvent.get(event.getEventId());

            // 도착 결과는 계획 유무와 무관하다. plan이 없다고 건너뛰면 정시 도착이
            // 실제보다 적게 잡힌다.
            String arrivalResult = execution == null ? null : execution.getArrivalResult();
            if (arrivalResult != null && !ARRIVAL_UNKNOWN.equals(arrivalResult)) {
                arrivalSampleCount++;
                if (ARRIVAL_ON_TIME.equals(arrivalResult)) {
                    onTimeCount++;
                }
            }

            PlanRevision plan = latestPlanByEvent.get(event.getEventId());
            if (plan == null) {
                continue;
            }
            UUID planId = plan.getPlanId();

            Integer outdoorMinutes = null;
            if (execution != null && execution.getActualOutdoorMinutes() != null) {
                outdoorMinutes = execution.getActualOutdoorMinutes();
            } else {
                PlanContext context = contextByPlan.get(planId);
                if (context != null && context.getEstimatedOutdoorMinutes() != null) {
                    outdoorMinutes = context.getEstimatedOutdoorMinutes();
                    anyEstimated = true;
                }
            }
            if (outdoorMinutes != null) {
                hasOutdoorDataPoint = true;
                totalOutdoor += outdoorMinutes;
                PlanWellnessScore score = scoreByPlan.get(planId);
                if (score != null && outdoorMinutes > 0) {
                    wisWeightedSum += score.getWisScore() * outdoorMinutes;
                    wisWeightTotal += outdoorMinutes;
                }
            }
            if (execution != null && execution.getRushLoadScore() != null) {
                rlsSum += execution.getRushLoadScore();
                rlsCount++;
            }
        }

        BigDecimal avgWis = wisWeightTotal > 0
                ? BigDecimal.valueOf(wisWeightedSum / wisWeightTotal) : null;
        BigDecimal avgRls = rlsCount > 0
                ? BigDecimal.valueOf(rlsSum / rlsCount) : null;
        DwlCalculation dwl = calculateDwl(avgWis, avgRls);

        // 데이터가 전혀 없으면(hasOutdoorDataPoint=false) "observed"라고 주장하지 않는다 —
        // 추정치를 관측치처럼 보여주지 않는다는 원칙(API 명세 §12.4)의 연장.
        boolean allObserved = hasOutdoorDataPoint && !anyEstimated;
        return new Aggregate(totalOutdoor, allObserved, onTimeCount, arrivalSampleCount,
                avgWis, avgRls, dwl.score(), dwl.band());
    }

    private record DwlCalculation(Short score, String band) {
    }

    private DwlCalculation calculateDwl(BigDecimal avgWis, BigDecimal avgRls) {
        // WIS와 RLS 모두 없으면 낮은 부담이라고 추론할 근거도 없다. 0/low로 저장하지 않고
        // API에 unknown/null을 반환해 데이터 부재와 실제 낮은 부담을 구분한다.
        if (avgWis == null && avgRls == null) {
            return new DwlCalculation(null, "unknown");
        }
        double wisComponent = avgWis != null ? avgWis.doubleValue() : 0;
        double rlsComponent = avgRls != null ? avgRls.doubleValue() : 0;
        short score = (short) Math.round(0.6 * wisComponent + 0.4 * rlsComponent);
        return new DwlCalculation(score, bandFor(score));
    }

    private boolean isManagedStatus(String value) {
        if (value == null) {
            return true;
        }
        try {
            return switch (EventStatus.valueOf(value.toUpperCase(Locale.ROOT))) {
                case CANCELLED, SKIPPED, UNRESOLVED -> false;
                default -> true;
            };
        } catch (IllegalArgumentException ignored) {
            // 기존 문자열 데이터가 새 enum보다 앞서 추가된 경우 요약에서 조용히 제외하지 않는다.
            return true;
        }
    }

    private String pickScenario(List<Event> events, Map<UUID, EventExecution> executionByEvent, Aggregate agg) {
        var config = wellnessConfigService.current();
        boolean anyRushedOrLate = events.stream()
                .map(e -> executionByEvent.get(e.getEventId()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(exec -> "rushed".equals(exec.getArrivalResult()) || "late".equals(exec.getArrivalResult()));
        if (anyRushedOrLate) {
            return "rushed";
        }
        if (events.size() >= config.cardDensityEventCount()) {
            return "density";
        }
        if (agg.totalOutdoorMinutes >= config.cardExposureOutdoorMinutes()) {
            return "exposure";
        }
        if (agg.dwlScore != null && agg.dwlScore < config.dwlBandMid()) {
            return "stable";
        }
        return "default";
    }

    private String bandFor(short dwlScore) {
        if (dwlScore <= 39) {
            return "low";
        }
        return dwlScore <= 69 ? "mid" : "high";
    }

    // TR-05·TR-09 — 승인된 템플릿에서만 문구를 고른다(자유 생성 LLM 미사용).
    private String messageFor(String scenario) {
        return switch (scenario) {
            case "rushed" -> "오늘은 이동이 촉박한 순간이 있었어요. 다음 일정은 조금 더 여유를 두고 준비해보는 건 어때요.";
            case "density" -> "오늘은 일정이 많아 바빴어요. 잠시 쉬어가는 시간을 가져보세요.";
            case "exposure" -> "자외선이 높은 시간대의 예상 야외 이동이 길었어요. 지금은 수분을 보충하고 편안하게 쉬어주세요.";
            case "stable" -> "오늘은 무리 없이 안정적으로 하루를 보냈어요.";
            default -> "오늘 하루도 수고하셨어요.";
        };
    }
}
