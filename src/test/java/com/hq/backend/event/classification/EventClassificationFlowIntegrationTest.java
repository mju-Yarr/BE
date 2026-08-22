package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.calendar.CalendarConnection;
import com.hq.backend.calendar.CalendarConnectionRepository;
import com.hq.backend.calendar.CalendarSyncService;
import com.hq.backend.calendar.DefaultGoogleCalendarSyncClient;
import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleEventDateTime;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.consent.UserConsent;
import com.hq.backend.consent.UserConsentRepository;
import com.hq.backend.event.Event;
import com.hq.backend.event.EventClassificationReview;
import com.hq.backend.event.EventClassificationReviewRepository;
import com.hq.backend.event.EventRepository;
import com.hq.backend.event.EventService;
import com.hq.backend.event.dto.EventReviewRequest;
import com.hq.backend.event.dto.EventUpdateRequest;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "openai.api-key=task10-fixture-key",
        "openai.model=gpt-4o-mini-2024-07-18",
        "openai.classification.enabled=true",
        "openai.classification.rollout-percent=100",
        "openai.classification.max-per-sync=5",
        "openai.classification.privacy-policy-version=privacy-v1"
})
@Import(EventClassificationFlowIntegrationTest.FlowTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EventClassificationFlowIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @TestConfiguration
    static class FlowTestConfiguration {
        @Bean
        @Primary
        RestClient flowCalendarRestClient() {
            return mock(RestClient.class);
        }

        @Bean
        @Primary
        DefaultGoogleCalendarSyncClient flowGoogleCalendarSyncClient(GoogleCalendarFixture fixture) {
            return fixture.client();
        }

        @Bean
        GoogleCalendarFixture googleCalendarFixture() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            return new GoogleCalendarFixture(server, new DefaultGoogleCalendarSyncClient(builder.build(),
                    "https://calendar.fixture/calendar/v3/calendars/primary/events"));
        }

        @Bean(name = "taskScheduler")
        @Primary
        @SuppressWarnings("unchecked")
        TaskScheduler noOpTaskScheduler() {
            TaskScheduler scheduler = mock(TaskScheduler.class);
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            doReturn(future).when(scheduler).schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class));
            doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
            doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
            doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
            doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
            doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
            return scheduler;
        }
    }

    record GoogleCalendarFixture(MockRestServiceServer server, DefaultGoogleCalendarSyncClient client) {
    }

    @org.springframework.beans.factory.annotation.Autowired private CalendarSyncService syncService;
    @org.springframework.beans.factory.annotation.Autowired private CalendarConnectionRepository connectionRepository;
    @org.springframework.beans.factory.annotation.Autowired private EventRepository eventRepository;
    @org.springframework.beans.factory.annotation.Autowired private EventClassificationReviewRepository reviewRepository;
    @org.springframework.beans.factory.annotation.Autowired private UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired private UserConsentRepository consentRepository;
    @org.springframework.beans.factory.annotation.Autowired private EventService eventService;
    @org.springframework.beans.factory.annotation.Autowired private BytesEncryptor encryptor;
    @org.springframework.beans.factory.annotation.Autowired private RestClient flowCalendarRestClient;
    @org.springframework.beans.factory.annotation.Autowired private GoogleCalendarFixture googleCalendarFixture;
    @org.springframework.beans.factory.annotation.Autowired private OpenAiEventClassifier openAiEventClassifier;

    private MockRestServiceServer openAiServer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reset(flowCalendarRestClient);
        RestClient.RequestBodyUriSpec post = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec body = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        when(flowCalendarRestClient.post()).thenReturn(post);
        when(post.uri(any(String.class))).thenReturn(body);
        when(body.contentType(any())).thenReturn(body);
        when(body.body(any(Object.class))).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
        when(response.body(GoogleTokenResponse.class)).thenReturn(new GoogleTokenResponse("access-token", null, null, 3600L));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.fixture/v1");
        openAiServer = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(openAiEventClassifier, "restClient", builder.build());
        googleCalendarFixture.server().reset();
    }

    @Test
    void two_page_google_sync_commits_title_free_pending_review_then_user_answer_changes_event() {
        User user = saveUser();
        agree(user);
        CalendarConnection connection = saveConnection(user);
        expectTwoGooglePages(
                googleEvent("google-page-1", "온라인 기획 회의", 1),
                googleEvent("google-page-2", "대면 고객 미팅", 2));
        expectClassifier("online");
        expectClassifier("offline");

        sync(connection, true);

        googleCalendarFixture.server().verify();
        List<Event> events = eventRepository.findAll().stream()
                .filter(event -> user.getUserId().equals(event.getUserId()))
                .filter(event -> event.getExternalEventId() != null && event.getExternalEventId().startsWith("google-page"))
                .toList();
        assertThat(events).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLocationState()).isEqualTo("undecided");
            assertThat(event.getDisplayLabel()).isNull();
            assertThat(event.getDestinationName()).isNull();
        });
        Event firstEvent = events.stream().filter(event -> "google-page-1".equals(event.getExternalEventId())).findFirst().orElseThrow();
        EventClassificationReview review = reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(firstEvent.getEventId()).orElseThrow();
        assertThat(review.getTitleSnapshot()).isNull();
        assertThat(review.getTitlePurgedAt()).isNotNull();
        assertThat(review.getSuggestedValue()).isEqualTo("online");

        eventService.answerReview(user.getUserId(), firstEvent.getEventId(), new EventReviewRequest(
                review.getReviewId(), "is_online", "online"));

        assertThat(eventRepository.findById(firstEvent.getEventId()).orElseThrow().getLocationState()).isEqualTo("not_required");
        assertThat(reviewRepository.findById(review.getReviewId()).orElseThrow().getUserAnswer()).isEqualTo("online");
        openAiServer.verify();
    }

    @Test
    void consent_revoke_provider_failure_malformed_duplicate_sync_and_user_patch_never_auto_change_event() {
        User noConsent = saveUser();
        CalendarConnection noConsentConnection = saveConnection(noConsent);
        expectSingleGooglePage(googleEvent("no-consent", "온라인 회의", 1));
        sync(noConsentConnection, true);
        verifyAndResetGoogleServer();
        Event noConsentEvent = eventFor(noConsent, "no-consent");
        assertUndecidedWithoutReview(noConsentEvent);

        User revoked = saveUser();
        agree(revoked);
        revoke(revoked);
        CalendarConnection revokedConnection = saveConnection(revoked);
        expectSingleGooglePage(googleEvent("revoked", "온라인 회의", 1));
        sync(revokedConnection, true);
        verifyAndResetGoogleServer();
        assertUndecidedWithoutReview(eventFor(revoked, "revoked"));

        User timeout = saveUser();
        agree(timeout);
        CalendarConnection timeoutConnection = saveConnection(timeout);
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("task10 timeout canary"); });
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[]}", MediaType.APPLICATION_JSON));
        expectClassifier("online");
        expectSingleGooglePage(googleEvent("timeout", "온라인 회의", 1));
        sync(timeoutConnection, true);
        verifyAndResetGoogleServer();
        assertUndecidedWithoutReview(eventFor(timeout, "timeout"));

        User malformed = saveUser();
        agree(malformed);
        CalendarConnection malformedConnection = saveConnection(malformed);
        expectSingleGooglePage(googleEvent("malformed", "대면 회의", 1));
        sync(malformedConnection, true);
        verifyAndResetGoogleServer();
        assertUndecidedWithoutReview(eventFor(malformed, "malformed"));

        User duplicate = saveUser();
        agree(duplicate);
        CalendarConnection duplicateConnection = saveConnection(duplicate);
        expectSingleGooglePage(googleEvent("duplicate", "온라인 회의", 1));
        sync(duplicateConnection, true);
        verifyAndResetGoogleServer();
        Event duplicateEvent = eventFor(duplicate, "duplicate");
        EventClassificationReview review = reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(duplicateEvent.getEventId()).orElseThrow();
        expectGooglePage(googleEvent("duplicate", "온라인 회의", 1), null, "task10-sync-token", null, "task10-sync-token");
        sync(duplicateConnection, true);
        verifyAndResetGoogleServer();
        assertThat(reviewRepository.findAll().stream().filter(item -> duplicateEvent.getEventId().equals(item.getEventId()))).hasSize(1);
        assertThat(duplicateEvent.getLocationState()).isEqualTo("undecided");

        eventService.update(duplicate.getUserId(), duplicateEvent.getEventId(), new EventUpdateRequest(
                null, null, null, null, null, null, "https://meeting.example", null, null, null));
        assertThatThrownBy(() -> eventService.answerReview(duplicate.getUserId(), duplicateEvent.getEventId(),
                new EventReviewRequest(review.getReviewId(), "is_online", "online")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode()).isEqualTo("REVIEW_ALREADY_CLOSED");
        assertThat(eventRepository.findById(duplicateEvent.getEventId()).orElseThrow().getLocationState()).isEqualTo("undecided");
        openAiServer.verify();
    }

    @Test
    void privacy_canary_in_provider_response_and_exception_never_reaches_logs_event_or_review(CapturedOutput output) {
        String canary = "TASK10_PRIVACY_CANARY_9f1f0c";
        User user = saveUser();
        agree(user);
        CalendarConnection connection = saveConnection(user);
        expectTwoGooglePages(
                googleEvent("canary-response", "안전한 온라인 회의", 1),
                googleEvent("canary-failure", "안전한 대면 회의", 2));
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(withSuccess(completedResponseText(canary), MediaType.APPLICATION_JSON));
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("provider-body"); });

        sync(connection, true);
        verifyAndResetGoogleServer();

        List<Event> events = eventRepository.findAll().stream().filter(event -> user.getUserId().equals(event.getUserId())).toList();
        assertThat(events).allSatisfy(event -> assertThat(java.util.stream.Stream.of(
                event.getDisplayLabel(), event.getDestinationName(), event.getMeetingUrl(), event.getExternalEventId()).toList())
                .noneMatch(value -> value != null && value.contains(canary)));
        assertThat(reviewRepository.findAll().stream().filter(review -> events.stream().anyMatch(event -> event.getEventId().equals(review.getEventId()))))
                .allSatisfy(review -> assertThat(java.util.stream.Stream.of(review.getTitleSnapshot(), review.getUserAnswer(), review.getSuggestedValue(),
                        review.getModelVersion(), review.getClassifierVersion(), review.getPromptVersion(), review.getSchemaVersion()).toList())
                        .noneMatch(value -> value != null && value.contains(canary)));
        assertThat(output).doesNotContain(canary, "provider-body", "task10-fixture-key");
        openAiServer.verify();
    }

    private void expectClassifier(String expected) {
        openAiServer.expect(requestTo("https://openai.fixture/v1/responses"))
                .andRespond(withSuccess(completedResponse(expected), MediaType.APPLICATION_JSON));
    }

    private void verifyAndResetGoogleServer() {
        googleCalendarFixture.server().verify();
        googleCalendarFixture.server().reset();
    }

    private void expectSingleGooglePage(GoogleCalendarSyncEvent event) {
        expectGooglePage(event, null, "task10-sync-token", null, null);
    }

    private void expectTwoGooglePages(GoogleCalendarSyncEvent first, GoogleCalendarSyncEvent second) {
        expectGooglePage(first, "task10-page-2", null, null, null);
        expectGooglePage(second, null, "task10-sync-token", "task10-page-2", null);
    }

    private void expectGooglePage(
            GoogleCalendarSyncEvent event, String nextPageToken, String nextSyncToken,
            String expectedPageToken, String expectedSyncToken) {
        googleCalendarFixture.server().expect(request -> assertGooglePageRequest(request, expectedPageToken, expectedSyncToken))
                .andRespond(withSuccess(googlePageResponse(event, nextPageToken, nextSyncToken), MediaType.APPLICATION_JSON));
    }

    private void assertGooglePageRequest(
            org.springframework.http.client.ClientHttpRequest request, String expectedPageToken, String expectedSyncToken) {
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        assertThat(request.getURI().getPath()).isEqualTo("/calendar/v3/calendars/primary/events");
        assertThat(query.getFirst("fields"))
                .isEqualTo("items(id,status,summary,start(dateTime),end(dateTime)),nextPageToken,nextSyncToken");
        if (expectedSyncToken == null) {
            assertThat(query.getFirst("singleEvents")).isEqualTo("true");
            assertThat(query.getFirst("orderBy")).isEqualTo("startTime");
            assertThat(query.getFirst("timeMin")).isNotBlank();
            assertThat(query.getFirst("timeMax")).isNotBlank();
        } else {
            assertThat(query.getFirst("syncToken")).isEqualTo(expectedSyncToken);
            assertThat(query).doesNotContainKeys("singleEvents", "orderBy", "timeMin", "timeMax");
        }
        assertThat(query.getFirst("pageToken")).isEqualTo(expectedPageToken);
        assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer access-token");
    }

    private String googlePageResponse(GoogleCalendarSyncEvent event, String nextPageToken, String nextSyncToken) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("items", List.of(java.util.Map.of(
                    "id", event.id(),
                    "status", event.status(),
                    "summary", event.summary(),
                    "start", java.util.Map.of("dateTime", event.start().dateTime().toString()),
                    "end", java.util.Map.of("dateTime", event.end().dateTime().toString()))));
            payload.put("nextPageToken", nextPageToken);
            payload.put("nextSyncToken", nextSyncToken);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new AssertionError("Google fixture JSON serialization failed", exception);
        }
    }

    private void sync(CalendarConnection connection, boolean classificationAllowed) {
        ReflectionTestUtils.invokeMethod(syncService, "syncConnection", connection.getCalendarConnectionId(), classificationAllowed);
    }

    private User saveUser() {
        return userRepository.saveAndFlush(User.builder().email("task10-" + UUID.randomUUID() + "@example.com")
                .nickname("task10-" + UUID.randomUUID().toString().substring(0, 8)).timezone("Asia/Seoul")
                .accountStatus("active").createdAt(NOW).build());
    }

    private void agree(User user) {
        consentRepository.saveAndFlush(UserConsent.builder().userId(user.getUserId()).consentType("privacy")
                .policyVersion("privacy-v1").action("agreed").isRequired(false).idempotencyKey(UUID.randomUUID())
                .recordedAt(NOW).build());
    }

    private void revoke(User user) {
        consentRepository.saveAndFlush(UserConsent.builder().userId(user.getUserId()).consentType("privacy")
                .policyVersion("privacy-v1").action("revoked").isRequired(false).idempotencyKey(UUID.randomUUID())
                .recordedAt(NOW.plusSeconds(1)).build());
    }

    private CalendarConnection saveConnection(User user) {
        return connectionRepository.saveAndFlush(CalendarConnection.builder().userId(user.getUserId()).provider("google")
                .externalAccountId("task10-account-" + UUID.randomUUID()).refreshTokenEnc(encryptor.encrypt("refresh".getBytes()))
                .connectedAt(NOW).build());
    }

    private GoogleCalendarSyncEvent googleEvent(String id, String title, int hourOffset) {
        Instant startsAt = NOW.plusSeconds(hourOffset * 3600L);
        return new GoogleCalendarSyncEvent(id, "confirmed", title, new GoogleEventDateTime(startsAt),
                new GoogleEventDateTime(startsAt.plusSeconds(3600)));
    }

    private Event eventFor(User user, String externalEventId) {
        return eventRepository.findAll().stream()
                .filter(event -> user.getUserId().equals(event.getUserId()))
                .filter(event -> externalEventId.equals(event.getExternalEventId()))
                .findFirst().orElseThrow();
    }

    private void assertUndecidedWithoutReview(Event event) {
        assertThat(event.getLocationState()).isEqualTo("undecided");
        assertThat(reviewRepository.findFirstByEventIdAndAnsweredAtIsNullOrderByAskedAtDesc(event.getEventId())).isEmpty();
    }

    private String completedResponse(String expected) {
        return completedResponseText("{\"questionType\":\"is_online\",\"suggestedValue\":\"%s\",\"confidence\":0.95}".formatted(expected));
    }

    private String completedResponseText(String text) {
        return "{\"status\":\"completed\",\"model\":\"gpt-4o-mini-2024-07-18\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":%s}]}]}"
                .formatted(OBJECT_MAPPER.valueToTree(text));
    }
}
