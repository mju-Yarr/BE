package com.hq.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventClassificationReviewRepository eventClassificationReviewRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void 일정을_생성하면_201과_displayLabel을_표시명으로_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-20T14:00:00+09:00","endsAt":"2026-08-20T15:00:00+09:00",
                 "locationState":"NOT_REQUIRED","sourceType":"INTERNAL","displayLabel":"강남역 미팅"}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("강남역 미팅"))
                .andExpect(jsonPath("$.locationState").value("not_required"))
                .andExpect(jsonPath("$.status").value("planned"))
                .andExpect(jsonPath("$.plan").value(nullValue()));
    }

    @Test
    void displayLabel이_없으면_destinationName으로_표시명을_대체한다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-20T14:00:00+09:00","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역"}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("강남역"));
    }

    @Test
    void 조회_수정_삭제가_소유자_기준으로_동작한다() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2026-08-21T10:00:00+09:00");

        mockMvc.perform(get("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId));

        mockMvc.perform(patch("/events/" + eventId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationState":"REQUIRED_MISSING"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationState").value("required_missing"));

        mockMvc.perform(delete("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void 다른_사용자의_일정에_접근하면_404() throws Exception {
        String ownerToken = signupAndLogin();
        String eventId = createEvent(ownerToken, "2026-08-22T10:00:00+09:00");
        String otherToken = signupAndLogin();

        mockMvc.perform(get("/events/" + eventId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    void destinationLat만_지정하고_Lng를_비우면_422() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2026-08-23T10:00:00+09:00");

        mockMvc.perform(patch("/events/" + eventId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destinationLat":37.498}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 생성시_destinationLat만_지정하고_Lng를_비우면_422() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-24T10:00:00+09:00","locationState":"NOT_REQUIRED",
                 "sourceType":"INTERNAL","destinationLat":37.498}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void endsAt이_startsAt보다_빠르면_422() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-24T14:00:00+09:00","endsAt":"2026-08-24T13:00:00+09:00",
                 "locationState":"NOT_REQUIRED","sourceType":"INTERNAL"}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 취소된_일정은_다음_일정으로_뜨지_않는다() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createEvent(accessToken, "2099-01-01T10:00:00+09:00");

        mockMvc.perform(delete("/events/" + eventId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/events/next").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NEXT_EVENT_NOT_FOUND"));
    }

    @Test
    void 분류_확인_응답에_online_offline이_아닌_값을_보내면_422() throws Exception {
        String accessToken = signupAndLogin();
        String eventId = createUndecidedEvent(accessToken, "2026-08-25T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        mockMvc.perform(post("/events/" + eventId + "/review")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewId":"%s","questionType":"is_online","userAnswer":"글쎄요"}
                                """.formatted(review.getReviewId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pending_review는_소유자_범위_및_상태에_맞는_title없는_projection만_반환한다() throws Exception {
        String ownerToken = signupAndLogin();
        String eligibleId = createUndecidedEvent(ownerToken, "2026-08-21T10:00:00+09:00");
        EventClassificationReview eligible = savePendingReview(UUID.fromString(eligibleId), Instant.parse("2026-08-20T03:00:00Z"));
        String answeredId = createUndecidedEvent(ownerToken, "2026-08-21T11:00:00+09:00");
        EventClassificationReview answered = savePendingReview(UUID.fromString(answeredId), Instant.parse("2026-08-20T03:01:00Z"));
        answered.setUserAnswer("online");
        answered.setAnsweredAt(Instant.parse("2026-08-20T04:00:00Z"));
        eventClassificationReviewRepository.saveAndFlush(answered);
        String ineligibleId = createEvent(ownerToken, "2026-08-21T12:00:00+09:00");
        savePendingReview(UUID.fromString(ineligibleId), Instant.parse("2026-08-20T03:02:00Z"));
        String boundaryId = createUndecidedEvent(ownerToken, "2026-08-27T00:00:00+09:00");
        savePendingReview(UUID.fromString(boundaryId), Instant.parse("2026-08-20T03:03:00Z"));
        String excludedId = createUndecidedEvent(ownerToken, "2026-08-21T13:00:00+09:00");
        savePendingReview(UUID.fromString(excludedId), Instant.parse("2026-08-20T03:04:00Z"));
        Event excluded = eventRepository.findById(UUID.fromString(excludedId)).orElseThrow();
        excluded.setAutoManageExcluded(true);
        eventRepository.saveAndFlush(excluded);
        String meetingId = createUndecidedEvent(ownerToken, "2026-08-21T14:00:00+09:00");
        savePendingReview(UUID.fromString(meetingId), Instant.parse("2026-08-20T03:05:00Z"));
        Event meeting = eventRepository.findById(UUID.fromString(meetingId)).orElseThrow();
        meeting.setMeetingUrl("https://meeting.example");
        eventRepository.saveAndFlush(meeting);
        String otherToken = signupAndLogin();
        String otherEventId = createUndecidedEvent(otherToken, "2026-08-21T09:00:00+09:00");
        savePendingReview(UUID.fromString(otherEventId), Instant.parse("2026-08-20T03:06:00Z"));

        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("from", "2026-08-20T00:00:00+09:00")
                        .param("to", "2026-08-27T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewId").value(eligible.getReviewId().toString()))
                .andExpect(jsonPath("$[0].eventId").value(eligibleId))
                .andExpect(jsonPath("$[0].questionType").value("is_online"))
                .andExpect(jsonPath("$[0].suggestedValue").value("online"))
                .andExpect(jsonPath("$[0].classificationConfidence").value(0.94))
                .andExpect(jsonPath("$[0].title").doesNotExist())
                .andExpect(jsonPath("$[0].titleSnapshot").doesNotExist())
                .andExpect(jsonPath("$[0].modelVersion").doesNotExist())
                .andExpect(jsonPath("$[0].provider").doesNotExist())
                .andExpect(jsonPath("$[0].classifierVersion").doesNotExist());
    }

    @Test
    void pending_review는_startsAt_askedAt_reviewId_순으로_정렬하고_유효하지않은_기간은_422이다() throws Exception {
        String token = signupAndLogin();
        String laterId = createUndecidedEvent(token, "2026-08-21T11:00:00+09:00");
        EventClassificationReview later = savePendingReview(UUID.fromString(laterId), Instant.parse("2026-08-20T03:00:00Z"));
        String firstId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview first = savePendingReview(UUID.fromString(firstId), Instant.parse("2026-08-20T04:00:00Z"));

        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-08-20T00:00:00+09:00")
                        .param("to", "2026-08-27T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewId").value(first.getReviewId().toString()))
                .andExpect(jsonPath("$[1].reviewId").value(later.getReviewId().toString()));

        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-08-27T00:00:00+09:00")
                        .param("to", "2026-08-20T00:00:00+09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-08-01T00:00:00+09:00")
                        .param("to", "2026-09-02T00:00:00+09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pending_review는_동일한_startsAt과_askedAt에서도_reviewId로_결정론적으로_정렬한다() throws Exception {
        String token = signupAndLogin();
        Instant askedAt = Instant.parse("2026-08-20T03:00:00Z");
        String firstEventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        String secondEventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview first = savePendingReview(UUID.fromString(firstEventId), askedAt);
        EventClassificationReview second = savePendingReview(UUID.fromString(secondEventId), askedAt);
        java.util.List<String> expected = java.util.stream.Stream.of(first, second)
                .map(review -> review.getReviewId().toString()).sorted().toList();

        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-08-20T00:00:00+09:00")
                        .param("to", "2026-08-22T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewId").value(expected.get(0)))
                .andExpect(jsonPath("$[1].reviewId").value(expected.get(1)));
    }

    @Test
    void reviewId가_다른_event에_속하거나_다른_사용자면_404이다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        String otherEventId = createUndecidedEvent(token, "2026-08-21T11:00:00+09:00");
        EventClassificationReview otherEventReview = savePendingReview(UUID.fromString(otherEventId), Instant.now());
        String otherToken = signupAndLogin();
        String foreignEventId = createUndecidedEvent(otherToken, "2026-08-21T12:00:00+09:00");
        EventClassificationReview foreignReview = savePendingReview(UUID.fromString(foreignEventId), Instant.now().plusSeconds(1));

        assertReviewError(token, eventId, otherEventReview.getReviewId(), "REVIEW_NOT_FOUND", 404);
        assertReviewError(token, eventId, foreignReview.getReviewId(), "REVIEW_NOT_FOUND", 404);
    }

    @Test
    void review_요청의_필수값이_비어있으면_422_validation_error이다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");

        mockMvc.perform(post("/events/" + eventId + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"is_online\",\"userAnswer\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // API 명세 §8.4의 요청 바디에는 reviewId가 없다 — 서버가 이 event의 미답변 리뷰를 찾는다.
    @Test
    void reviewId_없이_보내면_해당_event의_미답변_review에_답변된다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        mockMvc.perform(post("/events/" + eventId + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"is_online\",\"userAnswer\":\"offline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationState").value("required_missing"))
                .andExpect(jsonPath("$.reviewClosed").value(true));

        assertThat(eventClassificationReviewRepository.findById(review.getReviewId()).orElseThrow()
                .getAnsweredAt()).isNotNull();
    }

    @Test
    void reviewId_없이_보냈는데_미답변_review가_없으면_404이다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");

        mockMvc.perform(post("/events/" + eventId + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"is_online\",\"userAnswer\":\"online\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    void 닫힌_review는_409이고_현재_분류대상이_아닌_event는_stale_409이다() throws Exception {
        String token = signupAndLogin();
        String closedEventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview closed = savePendingReview(UUID.fromString(closedEventId), Instant.now());
        closed.setUserAnswer("online");
        closed.setAnsweredAt(Instant.now());
        eventClassificationReviewRepository.saveAndFlush(closed);
        String staleEventId = createEvent(token, "2026-08-21T11:00:00+09:00");
        EventClassificationReview stale = savePendingReview(UUID.fromString(staleEventId), Instant.now().plusSeconds(1));

        assertReviewError(token, closedEventId, closed.getReviewId(), "REVIEW_ALREADY_CLOSED", 409);
        assertReviewError(token, staleEventId, stale.getReviewId(), "REVIEW_STALE", 409);
    }

    @Test
    void online_offline_답변은_event와_review를_같이_확정한다() throws Exception {
        String token = signupAndLogin();
        String onlineEventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview online = savePendingReview(UUID.fromString(onlineEventId), Instant.now());
        String offlineEventId = createUndecidedEvent(token, "2026-08-21T11:00:00+09:00");
        EventClassificationReview offline = savePendingReview(UUID.fromString(offlineEventId), Instant.now().plusSeconds(1));

        answer(token, onlineEventId, online.getReviewId(), "online")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationState").value("not_required"));
        answer(token, offlineEventId, offline.getReviewId(), "offline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationState").value("required_missing"));

        EventClassificationReview reloadedOnline = eventClassificationReviewRepository.findById(online.getReviewId()).orElseThrow();
        EventClassificationReview reloadedOffline = eventClassificationReviewRepository.findById(offline.getReviewId()).orElseThrow();
        assertThat(eventRepository.findById(UUID.fromString(onlineEventId)).orElseThrow().getMeetingUrl()).isNull();
        assertThat(reloadedOnline.getUserAnswer()).isEqualTo("online");
        assertThat(reloadedOnline.getAnsweredAt()).isNotNull();
        assertThat(reloadedOffline.getUserAnswer()).isEqualTo("offline");
        assertThat(reloadedOffline.getAnsweredAt()).isNotNull();
    }

    /**
     * 명세 §3 S-11 "잘 모르겠어요 — 미해결로 남긴다. 재질문하지 않는다".
     *
     * <p>답변을 닫아야 다시 묻지 않고, 장소 필요 여부는 확정하지 않아야 한다. 둘 중
     * 하나만 지키면 규칙이 깨진다 — 닫지 않으면 재질문하고, 상태를 바꾸면 강제 확정이다.</p>
     */
    @Test
    void 잘_모르겠어요는_질문만_닫고_장소_필요여부는_확정하지_않는다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        answer(token, eventId, review.getReviewId(), "unknown")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationState").value("undecided"))
                .andExpect(jsonPath("$.reviewClosed").value(true));

        EventClassificationReview reloaded = eventClassificationReviewRepository
                .findById(review.getReviewId()).orElseThrow();
        assertThat(reloaded.getUserAnswer()).isEqualTo("unknown");
        assertThat(reloaded.getAnsweredAt()).isNotNull();
        // 제목 원문은 답변과 함께 폐기한다(ck_title_purged).
        assertThat(reloaded.getTitleSnapshot()).isNull();
        assertThat(eventRepository.findById(UUID.fromString(eventId)).orElseThrow().getLocationState())
                .isEqualTo("undecided");
    }

    @Test
    void 잘_모르겠어요로_닫은_질문은_다시_답할_수_없다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        answer(token, eventId, review.getReviewId(), "unknown").andExpect(status().isOk());

        assertReviewError(token, eventId, review.getReviewId(), "REVIEW_ALREADY_CLOSED", 409);
    }

    @Test
    void 미해결로_닫은_일정은_pending_목록에서_빠진다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-08-21T00:00:00+09:00")
                        .param("to", "2026-08-22T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewId").value(review.getReviewId().toString()))
                .andExpect(jsonPath("$[0].questionType").value("is_online"));

        answer(token, eventId, review.getReviewId(), "unknown").andExpect(status().isOk());

        mockMvc.perform(get("/events/reviews/pending")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-08-21T00:00:00+09:00")
                        .param("to", "2026-08-22T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 사용자_PATCH가_분류_eligibility를_제거하면_pending_review를_사용자_답변없이_닫는다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        mockMvc.perform(patch("/events/" + eventId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"autoManageExcluded\":true}"))
                .andExpect(status().isOk());

        EventClassificationReview reloaded = eventClassificationReviewRepository.findById(review.getReviewId()).orElseThrow();
        assertThat(reloaded.getAnsweredAt()).isNotNull();
        assertThat(reloaded.getUserAnswer()).isNull();
        assertThat(reloaded.getTitleSnapshot()).isNull();
    }

    @Test
    void 취소된_event의_pending_review는_닫지않고_답변을_stale_409으로_거절한다() throws Exception {
        String token = signupAndLogin();
        String eventId = createUndecidedEvent(token, "2026-08-21T10:00:00+09:00");
        EventClassificationReview review = savePendingReview(UUID.fromString(eventId), Instant.now());

        mockMvc.perform(delete("/events/" + eventId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertReviewError(token, eventId, review.getReviewId(), "REVIEW_STALE", 409);
        EventClassificationReview reloaded = eventClassificationReviewRepository.findById(review.getReviewId()).orElseThrow();
        assertThat(reloaded.getAnsweredAt()).isNull();
        assertThat(reloaded.getUserAnswer()).isNull();
    }

    @Test
    void 기간_조회는_범위_안의_일정만_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        String insideId = createEvent(accessToken, "2026-09-01T10:00:00+09:00");
        createEvent(accessToken, "2026-10-01T10:00:00+09:00");

        mockMvc.perform(get("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("from", "2026-08-31T00:00:00+09:00")
                        .param("to", "2026-09-02T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(insideId));
    }

    @Test
    void 기간_조회는_31일을_초과하면_422이다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("from", "2026-08-01T00:00:00+09:00")
                        .param("to", "2026-09-02T00:00:00+09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private String createEvent(String accessToken, String startsAt) throws Exception {
        String body = """
                {"startsAt":"%s","locationState":"NOT_REQUIRED","sourceType":"INTERNAL"}
                """.formatted(startsAt);
        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.eventId");
    }

    private String createUndecidedEvent(String accessToken, String startsAt) throws Exception {
        String body = """
                {"startsAt":"%s","locationState":"UNDECIDED","sourceType":"INTERNAL"}
                """.formatted(startsAt);
        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.eventId");
    }

    private EventClassificationReview savePendingReview(UUID eventId, Instant askedAt) {
        return eventClassificationReviewRepository.saveAndFlush(EventClassificationReview.builder()
                .eventId(eventId).questionType("is_online").suggestedValue("online")
                .classificationConfidence(new java.math.BigDecimal("0.9400"))
                .askedAt(askedAt).titlePurgedAt(askedAt)
                .provider("openai").modelVersion("gpt-5-mini")
                .classifierVersion("classifier-v1").promptVersion("prompt-v1").schemaVersion("schema-v1")
                .build());
    }

    private org.springframework.test.web.servlet.ResultActions answer(
            String token, String eventId, UUID reviewId, String userAnswer) throws Exception {
        return mockMvc.perform(post("/events/" + eventId + "/review")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reviewId":"%s","questionType":"is_online","userAnswer":"%s"}
                        """.formatted(reviewId, userAnswer)));
    }

    private void assertReviewError(String token, String eventId, UUID reviewId, String code, int expectedStatus) throws Exception {
        answer(token, eventId, reviewId, "online")
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        String response = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }
}
