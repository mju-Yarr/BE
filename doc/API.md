# ENSOM Backend API 명세서

> **Source of truth:** BE commit `e36cffde1bd7135e59c9bc613c207ef2e0349d81`. 이 문서는 `tools/generate_api_spec.py`가 Spring MVC controller, DTO, Bean Validation, Jackson enum annotation에서 생성한다.
> 생성: `python3 tools/generate_api_spec.py` · endpoint count 검증: `python3 tools/generate_api_spec.py --check`.

## 1. 적용 범위와 전송 규약

- Public base URL: `https://api.ensom.shop/v1`. nginx가 `/v1`을 제거해 Spring controller로 전달한다.
- 요청/응답은 별도 표기가 없으면 JSON (`application/json`)이다. UUID는 RFC 4122 문자열, 시간은 ISO-8601 (`Instant`/`OffsetDateTime`), 날짜는 `YYYY-MM-DD` (`LocalDate`)다.
- `Bearer JWT` endpoint는 `Authorization: Bearer <access-token>`이 필수다. `/auth/**`와 health endpoint만 public이며, 그 외 token 누락/무효는 `401 UNAUTHENTICATED`다.
- Enum은 아래 schema 표의 wire value를 사용한다. `@JsonValue` enum은 lower-snake-case로 직렬화된다.
- 이 문서는 controller source에서 생성되므로 구현된 endpoint만 담는다. 여기 없는 경로는 아직 없는 경로다.

## 2. 오류 응답

`ApiException` 및 validation 오류는 아래 envelope를 쓴다.

```json
{"error":{"code":"INVALID_REQUEST","message":"설명","retryable":false}}
```

| HTTP | 대표 code | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST`, `INVALID_EMAIL` | Bean Validation, 누락/형식 오류, JSON parse 오류 |
| 401 | `UNAUTHENTICATED`, `INVALID_TOKEN`, `INVALID_CREDENTIALS` | token/credentials 오류 |
| 403 | `EMAIL_VERIFICATION_REQUIRED` | 인증은 됐지만 이메일 인증 필요 |
| 404 | `*_NOT_FOUND` | 소유 resource를 찾지 못함 |
| 409 | `*_ALREADY_*`, `PLAN_NOT_ACTIVE`, `REVIEW_STALE` | 충돌/경합 |
| 422 | `VALIDATION_ERROR`, `SENSITIVE_CHIP_REJECTED` | domain validation 오류 |
| 500 | `INTERNAL_ERROR` | 예기치 않은 서버 오류 (legacy envelope: `error` string) |
| 503 | `*_UNAVAILABLE`, `PLAN_CREATION_FAILED` | 외부 provider/plan engine 사용 불가 |

## 3. Endpoint

생성 기준 public endpoint 수: **91**.

### auth

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| POST | `/v1/auth/email/signup` | Public | body `request`: `SignupRequest` | `201 CREATED` | `SignupResponse` | `AuthController.java#signup` |
| POST | `/v1/auth/email/verification/send` | Public | body `request`: `EmailVerificationSendRequest`, parameter `httpRequest`: `HttpServletRequest` | `202 ACCEPTED` | `EmailVerificationSendResponse` | `AuthController.java#sendVerificationCode` |
| POST | `/v1/auth/email/verification/confirm` | Public | body `request`: `EmailVerificationConfirmRequest`, parameter `httpRequest`: `HttpServletRequest` | `200 OK` | `EmailVerificationConfirmResponse` | `AuthController.java#confirmVerificationCode` |
| POST | `/v1/auth/email/verify` | Public | body `request`: `VerifyEmailRequest` | `204 NO_CONTENT` | `void` | `AuthController.java#verifyEmail` |
| GET | `/v1/auth/email/verify` | Public | query `token`: `String` | `200 OK` | `String` | `AuthController.java#verifyEmailLink` |
| POST | `/v1/auth/email/verify/resend` | Public | body `request`: `ResendVerificationRequest` | `202 ACCEPTED` | `void` | `AuthController.java#resendEmailVerification` |
| POST | `/v1/auth/email/login` | Public | body `request`: `LoginRequest` | `200 OK` | `TokenResponse` | `AuthController.java#login` |
| POST | `/v1/auth/google` | Public | body `request`: `GoogleLoginRequest` | `200 OK` | `TokenResponse` | `AuthController.java#loginWithGoogle` |
| POST | `/v1/auth/refresh` | Public | body `body`: `Map<String, String>` | `200 OK` | `TokenResponse` | `AuthController.java#refresh` |
| POST | `/v1/auth/logout` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `AuthController.java#logout` |
| POST | `/v1/auth/password/reset-request` | Public | body `request`: `PasswordResetRequest` | `202 ACCEPTED` | `void` | `AuthController.java#requestPasswordReset` |
| POST | `/v1/auth/password/reset` | Public | body `request`: `PasswordResetExecuteRequest` | `204 NO_CONTENT` | `void` | `AuthController.java#resetPassword` |
| GET | `/v1/auth/check-nickname` | Public | query `value`: `String` | `200 OK` | `CheckNicknameResponse` | `AuthController.java#checkNickname` |

### calendar

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/calendar/connections` | Bearer JWT | — | `200 OK` | `List<CalendarConnectionSummaryResponse>` | `CalendarController.java#connections` |
| PATCH | `/v1/calendar/sources/{sourceId}` | Bearer JWT | path `sourceId`: `UUID`, body `request`: `CalendarSourcePatchRequest` | `200 OK` | `CalendarSourceResponse` | `CalendarController.java#patchSource` |
| POST | `/v1/calendar/sources/{sourceId}/default` | Bearer JWT | path `sourceId`: `UUID` | `200 OK` | `CalendarSourceResponse` | `CalendarController.java#selectDefaultSource` |
| POST | `/v1/calendar/google/connect` | Bearer JWT | body `request`: `ConnectCalendarRequest` | `200 OK` | `CalendarConnectionResponse` | `CalendarController.java#connect` |
| DELETE | `/v1/calendar/google` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `CalendarController.java#disconnect` |
| GET | `/v1/calendar/google/status` | Bearer JWT | — | `200 OK` | `CalendarConnectionStatusResponse` | `CalendarController.java#googleConnectionStatus` |
| POST | `/v1/calendar/sync` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `CalendarController.java#sync` |
| GET | `/v1/calendar/density` | Bearer JWT | query `date`: `LocalDate` | `200 OK` | `DensityResponse` | `CalendarController.java#density` |

### consents

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| POST | `/v1/consents` | Bearer JWT | header `Idempotency-Key`: `UUID`, body `request`: `ConsentRequest` | `201 CREATED` | `ConsentResponse` | `ConsentController.java#record` |

### environment

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/environment/current` | Bearer JWT | — | `200 OK` | `EnvironmentResponse` | `EnvironmentController.java#current` |

### events

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/events` | Bearer JWT | query `from`: `OffsetDateTime`, query `to`: `OffsetDateTime` | `200 OK` | `List<EventResponse>` | `EventController.java#list` |
| GET | `/v1/events/next` | Bearer JWT | — | `200 OK` | `EventResponse` | `EventController.java#next` |
| GET | `/v1/events/reviews/pending` | Bearer JWT | query `from`: `OffsetDateTime`, query `to`: `OffsetDateTime` | `200 OK` | `List<PendingEventReviewResponse>` | `EventController.java#pendingReviews` |
| POST | `/v1/events` | Bearer JWT | body `request`: `EventCreateRequest` | `201 CREATED` | `EventResponse` | `EventController.java#create` |
| GET | `/v1/events/{eventId}` | Bearer JWT | path `eventId`: `UUID` | `200 OK` | `EventResponse` | `EventController.java#get` |
| PATCH | `/v1/events/{eventId}` | Bearer JWT | path `eventId`: `UUID`, body `request`: `EventUpdateRequest` | `200 OK` | `EventResponse` | `EventController.java#update` |
| DELETE | `/v1/events/{eventId}` | Bearer JWT | path `eventId`: `UUID` | `204 NO_CONTENT` | `void` | `EventController.java#delete` |
| POST | `/v1/events/{eventId}/review` | Bearer JWT | path `eventId`: `UUID`, body `request`: `EventReviewRequest` | `200 OK` | `EventReviewResponse` | `EventController.java#review` |
| GET | `/v1/events/{eventId}/execution` | Bearer JWT | path `eventId`: `UUID` | `200 OK` | `EventExecutionResponse` | `EventOutcomeController.java#getExecution` |
| POST | `/v1/events/{eventId}/feedback` | Bearer JWT | path `eventId`: `UUID`, body `request`: `EventFeedbackRequest` | `200 OK` | `EventFeedbackResponse` | `EventOutcomeController.java#submitFeedback` |
| GET | `/v1/events/{eventId}/plans/latest` | Bearer JWT | path `eventId`: `UUID` | `200 OK` | `PlanDetailResponse` | `EventPlanController.java#latest` |
| POST | `/v1/events/{eventId}/plan/recalculate` | Bearer JWT | path `eventId`: `UUID`, body `request`: `RecalculateRequest` | `200 OK` | `PlanRecalculateResponse` | `EventPlanController.java#recalculate` |

### me

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/me/bookmarks` | Bearer JWT | query `folder`: `String` | `200 OK` | `List<BookmarkResponse>` | `BookmarkController.java#list` |
| POST | `/v1/me/bookmarks` | Bearer JWT | body `request`: `BookmarkCreateRequest` | `201 CREATED` | `BookmarkResponse` | `BookmarkController.java#create` |
| PATCH | `/v1/me/bookmarks/{id}` | Bearer JWT | path `id`: `UUID`, body `request`: `BookmarkPatchRequest` | `200 OK` | `BookmarkResponse` | `BookmarkController.java#patch` |
| POST | `/v1/me/bookmarks/bulk-delete` | Bearer JWT | body `request`: `BookmarkBulkDeleteRequest` | `204 NO_CONTENT` | `void` | `BookmarkController.java#bulkDelete` |
| DELETE | `/v1/me/bookmarks/{id}` | Bearer JWT | path `id`: `UUID` | `204 NO_CONTENT` | `void` | `BookmarkController.java#delete` |
| GET | `/v1/me/recent-destinations` | Bearer JWT | query `limit`: `int` | `200 OK` | `List<RecentDestinationResponse>` | `RecentDestinationController.java#list` |
| DELETE | `/v1/me/recent-destinations` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `RecentDestinationController.java#clear` |
| GET | `/v1/me/bootstrap` | Bearer JWT | — | `200 OK` | `BootstrapResponse` | `BootstrapController.java#bootstrap` |
| GET | `/v1/me/onboarding` | Bearer JWT | — | `200 OK` | `OnboardingProgressResponse` | `OnboardingController.java#get` |
| PATCH | `/v1/me/onboarding` | Bearer JWT | body `request`: `OnboardingProgressRequest` | `200 OK` | `OnboardingProgressResponse` | `OnboardingController.java#update` |
| POST | `/v1/me/onboarding/complete` | Bearer JWT | — | `200 OK` | `OnboardingProgressResponse` | `OnboardingController.java#complete` |
| POST | `/v1/me/onboarding/coachmark-seen` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `OnboardingController.java#coachmarkSeen` |
| GET | `/v1/me/permissions` | Bearer JWT | — | `200 OK` | `List<PermissionResponse>` | `UserPermissionController.java#get` |
| PATCH | `/v1/me/permissions` | Bearer JWT | body `request`: `PermissionUpdateRequest` | `200 OK` | `List<PermissionResponse>` | `UserPermissionController.java#update` |
| GET | `/v1/me/personalization` | Bearer JWT | — | `200 OK` | `PersonalizationResponse` | `PersonalizationController.java#get` |
| DELETE | `/v1/me/personalization` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `PersonalizationController.java#reset` |
| POST | `/v1/me/personalization/revert` | Bearer JWT | — | `200 OK` | `PersonalizationResponse` | `PersonalizationController.java#revert` |
| GET | `/v1/me/settings` | Bearer JWT | — | `200 OK` | `SettingsResponse` | `SettingsController.java#get` |
| PATCH | `/v1/me/settings` | Bearer JWT | body `request`: `SettingsRequest` | `200 OK` | `SettingsResponse` | `SettingsController.java#update` |
| GET | `/v1/me/providers` | Bearer JWT | — | `200 OK` | `List<ProviderResponse>` | `AccountController.java#listProviders` |
| POST | `/v1/me/providers` | Bearer JWT | body `request`: `LinkProviderRequest` | `201 CREATED` | `ProviderResponse` | `AccountController.java#linkProvider` |
| DELETE | `/v1/me/providers/{identityId}` | Bearer JWT | path `identityId`: `UUID` | `204 NO_CONTENT` | `void` | `AccountController.java#unlinkProvider` |
| GET | `/v1/me/sessions` | Bearer JWT | header `X-Refresh-Token`: `String` | `200 OK` | `List<SessionResponse>` | `AccountController.java#listSessions` |
| DELETE | `/v1/me/sessions/{id}` | Bearer JWT | path `id`: `UUID`, header `X-Refresh-Token`: `String` | `204 NO_CONTENT` | `void` | `AccountController.java#revokeSession` |
| DELETE | `/v1/me/sessions` | Bearer JWT | header `X-Refresh-Token`: `String` | `204 NO_CONTENT` | `void` | `AccountController.java#revokeOtherSessions` |
| DELETE | `/v1/me/action-logs` | Bearer JWT | — | `204 NO_CONTENT` | `void` | `AccountController.java#deleteActionLogs` |
| DELETE | `/v1/me` | Bearer JWT | — | `200 OK` | `AccountDeletionResponse` | `UserController.java#withdraw` |
| PATCH | `/v1/me/password` | Bearer JWT | body `request`: `ChangePasswordRequest`, header `X-Refresh-Token`: `String` | `204 NO_CONTENT` | `void` | `UserController.java#changePassword` |
| PATCH | `/v1/me/nickname` | Bearer JWT | body `request`: `ChangeNicknameRequest` | `200 OK` | `ChangeNicknameResponse` | `UserController.java#changeNickname` |
| POST | `/v1/me/email/change-request` | Bearer JWT | body `request`: `EmailChangeRequest` | `202 ACCEPTED` | `void` | `UserController.java#requestEmailChange` |
| POST | `/v1/me/email/change-confirm` | Public | body `request`: `EmailChangeConfirmRequest` | `204 NO_CONTENT` | `void` | `UserController.java#confirmEmailChange` |
| GET | `/v1/me/wellness-prefs` | Bearer JWT | — | `200 OK` | `List<WellnessPrefResponse>` | `WellnessPrefController.java#get` |
| PATCH | `/v1/me/wellness-prefs` | Bearer JWT | body `request`: `WellnessPrefsRequest` | `200 OK` | `List<WellnessPrefResponse>` | `WellnessPrefController.java#update` |

### notifications

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/notifications/today` | Bearer JWT | — | `200 OK` | `List<NotificationResponse>` | `NotificationController.java#getToday` |
| POST | `/v1/notifications/{notificationId}/respond` | Bearer JWT | path `notificationId`: `UUID`, body `request`: `NotificationRespondRequest` | `200 OK` | `Void` | `NotificationController.java#respond` |

### places

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/places` | Bearer JWT | — | `200 OK` | `List<PlaceResponse>` | `PlaceController.java#list` |
| POST | `/v1/places` | Bearer JWT | body `request`: `PlaceRequest` | `201 CREATED` | `PlaceResponse` | `PlaceController.java#create` |
| PATCH | `/v1/places/{placeId}` | Bearer JWT | path `placeId`: `UUID`, body `request`: `PlaceUpdateRequest` | `200 OK` | `PlaceResponse` | `PlaceController.java#update` |
| DELETE | `/v1/places/{placeId}` | Bearer JWT | path `placeId`: `UUID` | `204 NO_CONTENT` | `void` | `PlaceController.java#delete` |

### plans

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/plans/{planId}` | Bearer JWT | path `planId`: `UUID` | `200 OK` | `PlanDetailResponse` | `PlanController.java#get` |
| PATCH | `/v1/plans/{planId}` | Bearer JWT | path `planId`: `UUID`, body `request`: `PlanPatchRequest` | `200 OK` | `PlanDetailResponse` | `PlanController.java#patch` |
| GET | `/v1/plans/{planId}/routes` | Bearer JWT | path `planId`: `UUID` | `200 OK` | `List<RouteOptionResponse>` | `PlanController.java#routes` |
| POST | `/v1/plans/{planId}/routes/select` | Bearer JWT | path `planId`: `UUID`, body `request`: `RouteSelectRequest` | `200 OK` | `PlanDetailResponse` | `PlanController.java#selectRoute` |
| POST | `/v1/plans/{planId}/actions` | Bearer JWT | path `planId`: `UUID`, body `request`: `ActionBatchRequest` | `200 OK` | `ActionBatchResponse` | `PlanController.java#submitActions` |
| POST | `/v1/plans/{planId}/prep-items/{planPrepItemId}/resolve` | Bearer JWT | path `planId`: `UUID`, path `planPrepItemId`: `UUID`, body `request`: `PrepItemResolveRequest` | `200 OK` | `PrepItemResolveResponse` | `PlanController.java#resolvePrepItem` |
| POST | `/v1/plans/{planId}/wellness-actions/{wellnessActionId}/resolve` | Bearer JWT | path `planId`: `UUID`, path `wellnessActionId`: `UUID`, body `request`: `WellnessActionResolveRequest` | `200 OK` | `WellnessActionResolveResponse` | `PlanController.java#resolveWellnessAction` |
| GET | `/v1/plans/today` | Bearer JWT | — | `200 OK` | `TodayPlanResponse` | `TodayPlanController.java#today` |

### prep-items

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/prep-items` | Bearer JWT | — | `200 OK` | `List<PrepRuleResponse>` | `PrepRuleController.java#list` |
| POST | `/v1/prep-items` | Bearer JWT | body `request`: `PrepRuleRequest` | `201 CREATED` | `PrepRuleResponse` | `PrepRuleController.java#create` |
| PATCH | `/v1/prep-items/{prepRuleId}` | Bearer JWT | path `prepRuleId`: `UUID`, body `request`: `PrepRuleUpdateRequest` | `200 OK` | `PrepRuleResponse` | `PrepRuleController.java#update` |
| DELETE | `/v1/prep-items/{prepRuleId}` | Bearer JWT | path `prepRuleId`: `UUID` | `204 NO_CONTENT` | `void` | `PrepRuleController.java#delete` |

### push-devices

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| POST | `/v1/push-devices` | Bearer JWT | body `request`: `RegisterPushDeviceRequest` | `201 CREATED` | `PushDeviceResponse` | `PushDeviceController.java#register` |

### routes

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/routes/search` | Bearer JWT | query `originPlaceId`: `UUID`, query `originLat`: `Double`, query `originLng`: `Double`, query `destLat`: `Double`, query `destLng`: `Double`, query `destName`: `String`, query `anchorMode`: `String`, query `at`: `OffsetDateTime` | `200 OK` | `List<RouteSearchResponse>` | `RouteSearchController.java#search` |

### summary

| Method | Path | Auth | 입력 | Success | Response | Handler |
|---|---|---|---|---|---|---|
| GET | `/v1/summary/daily` | Bearer JWT | query `date`: `LocalDate` | `200 OK` | `DailySummaryResponse` | `DailySummaryController.java#get` |
| POST | `/v1/summary/daily/{summaryId}/viewed` | Bearer JWT | path `summaryId`: `UUID` | `200 OK` | `DailySummaryResponse` | `DailySummaryController.java#markViewed` |
| GET | `/v1/summary/weekly` | Bearer JWT | query `date`: `LocalDate` | `200 OK` | `WeeklySummaryResponse` | `WeeklySummaryController.java#get` |

## 4. Request / response schema

`—`는 source에 Bean Validation annotation이 없다는 뜻이며, optional을 의미하지는 않는다. nullability와 domain rule은 endpoint service의 추가 검증을 따른다.

### `AccountDeletionResponse`

| Field | Java type | Validation |
|---|---|---|
| `deleted` | `List<String>` | — |
| `retained` | `List<String>` | — |
| `retentionReason` | `String` | — |

### `ActionBatchRequest`

| Field | Java type | Validation |
|---|---|---|
| `actions` | `List<ActionItem>` | NotEmpty; Size(max = 100) |

### `ActionBatchResponse`

| Field | Java type | Validation |
|---|---|---|
| `accepted` | `int` | — |
| `duplicated` | `int` | — |
| `eventStatus` | `String` | — |
| `plan` | `PlanDetailResponse` | — |

### `BookmarkBulkDeleteRequest`

| Field | Java type | Validation |
|---|---|---|
| `bookmarkIds` | `List<UUID>` | NotEmpty |

### `BookmarkCreateRequest`

| Field | Java type | Validation |
|---|---|---|
| `placeName` | `String` | NotBlank |
| `address` | `String` | — |
| `lat` | `BigDecimal` | NotNull |
| `lng` | `BigDecimal` | NotNull |
| `folder` | `String` | — |
| `sortOrder` | `Integer` | Positive |

### `BookmarkPatchRequest`

| Field | Java type | Validation |
|---|---|---|
| `placeName` | `String` | — |
| `address` | `String` | — |
| `lat` | `BigDecimal` | — |
| `lng` | `BigDecimal` | — |
| `folder` | `String` | — |
| `sortOrder` | `Integer` | Positive |

### `BookmarkResponse`

| Field | Java type | Validation |
|---|---|---|
| `bookmarkId` | `UUID` | — |
| `placeName` | `String` | — |
| `address` | `String` | — |
| `lat` | `BigDecimal` | — |
| `lng` | `BigDecimal` | — |
| `folder` | `String` | — |
| `sortOrder` | `int` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |

### `BootstrapResponse`

| Field | Java type | Validation |
|---|---|---|
| `user` | `UserSummary` | — |
| `settings` | `SettingsSummary` | — |
| `gate` | `PermissionsAndOnboarding` | — |
| `permissions` | `List<PermissionResponse>` | — |
| `places` | `List<PlaceSummary>` | — |
| `prepItems` | `List<PrepRuleResponse>` | — |
| `todayPlan` | `TodayPlanResponse` | — |
| `engineConfig` | `EngineConfigSummary` | — |

### `CalendarConnectionResponse`

| Field | Java type | Validation |
|---|---|---|
| `provider` | `String` | — |
| `externalAccountId` | `String` | — |
| `connectedAt` | `Instant` | — |

### `CalendarConnectionStatusResponse`

| Field | Java type | Validation |
|---|---|---|
| `connected` | `boolean` | — |

### `CalendarConnectionSummaryResponse`

| Field | Java type | Validation |
|---|---|---|
| `calendarConnectionId` | `UUID` | — |
| `provider` | `String` | — |
| `externalAccountId` | `String` | — |
| `connectedAt` | `Instant` | — |
| `lastSyncedAt` | `Instant` | — |
| `sources` | `List<CalendarSourceResponse>` | — |

### `CalendarSourcePatchRequest`

| Field | Java type | Validation |
|---|---|---|
| `syncEnabled` | `Boolean` | NotNull |

### `CalendarSourceResponse`

| Field | Java type | Validation |
|---|---|---|
| `calendarSourceId` | `UUID` | — |
| `displayName` | `String` | — |
| `writable` | `boolean` | — |
| `defaultSource` | `boolean` | — |
| `syncEnabled` | `boolean` | — |

### `ChangeNicknameRequest`

| Field | Java type | Validation |
|---|---|---|
| `nickname` | `String` | NotBlank |

### `ChangeNicknameResponse`

| Field | Java type | Validation |
|---|---|---|
| `nickname` | `String` | — |

### `ChangePasswordRequest`

| Field | Java type | Validation |
|---|---|---|
| `currentPassword` | `String` | NotBlank |
| `newPassword` | `String` | NotBlank |

### `CheckNicknameResponse`

| Field | Java type | Validation |
|---|---|---|
| `available` | `boolean` | — |

### `ConnectCalendarRequest`

| Field | Java type | Validation |
|---|---|---|
| `authCode` | `String` | NotBlank |

### `ConsentRequest`

| Field | Java type | Validation |
|---|---|---|
| `consentType` | `ConsentType` — enum: `terms, privacy, location, marketing` | NotNull |
| `agreed` | `Boolean` | NotNull |
| `policyVersion` | `String` | NotBlank |

### `ConsentResponse`

| Field | Java type | Validation |
|---|---|---|
| `id` | `UUID` | — |
| `consentType` | `ConsentType` — enum: `terms, privacy, location, marketing` | — |
| `agreed` | `Boolean` | — |
| `recordedAt` | `Instant` | — |

### `DailySummaryResponse`

| Field | Java type | Validation |
|---|---|---|
| `summaryId` | `UUID` | — |
| `summaryDate` | `LocalDate` | — |
| `eventCount` | `int` | — |
| `totalOutdoorMinutes` | `int` | — |
| `outdoorSource` | `String` | — |
| `onTimeCount` | `int` | — |
| `arrivalSampleCount` | `int` | — |
| `dwlBand` | `String` | — |
| `dwlScore` | `Short` | — |
| `cardScenario` | `String` | — |
| `message` | `String` | — |
| `isViewed` | `boolean` | — |

### `DensityResponse`

| Field | Java type | Validation |
|---|---|---|
| `calendarSynced` | `boolean` | — |
| `blocks` | `List<BusyBlockResponse>` | — |

### `EmailChangeConfirmRequest`

| Field | Java type | Validation |
|---|---|---|
| `token` | `String` | NotBlank |

### `EmailChangeRequest`

| Field | Java type | Validation |
|---|---|---|
| `newEmail` | `String` | NotBlank; Email |
| `password` | `String` | NotBlank |

### `EmailVerificationConfirmRequest`

| Field | Java type | Validation |
|---|---|---|
| `email` | `String` | NotBlank; Email |
| `code` | `String` | NotBlank; Pattern(regexp = "\\d{6}") |

### `EmailVerificationConfirmResponse`

| Field | Java type | Validation |
|---|---|---|
| `verificationTicket` | `String` | — |
| `expiresAt` | `Instant` | — |

### `EmailVerificationSendRequest`

| Field | Java type | Validation |
|---|---|---|
| `email` | `String` | NotBlank; Email |

### `EmailVerificationSendResponse`

| Field | Java type | Validation |
|---|---|---|
| `challengeId` | `UUID` | — |
| `expiresAt` | `Instant` | — |

### `EnvironmentResponse`

| Field | Java type | Validation |
|---|---|---|
| `temperature` | `Integer` | — |
| `sky` | `String` | — |
| `pm10Grade` | `String` | — |
| `pm25Grade` | `String` | — |
| `uvIndex` | `Integer` | — |

### `EventCreateRequest`

| Field | Java type | Validation |
|---|---|---|
| `startsAt` | `Instant` | NotNull |
| `endsAt` | `Instant` | — |
| `locationState` | `LocationState` — enum: `required_resolved, required_missing, not_required, undecided` | NotNull |
| `destinationName` | `String` | — |
| `destinationAddress` | `String` | — |
| `destinationLat` | `Double` | — |
| `destinationLng` | `Double` | — |
| `meetingUrl` | `String` | — |
| `eventKind` | `String` | — |
| `sourceType` | `SourceType` — enum: `internal, external, map_search` | NotNull |
| `anchorMode` | `String` | — |
| `originPlaceId` | `UUID` | — |
| `selectedRouteOptionId` | `UUID` | — |
| `displayLabel` | `String` | — |
| `writeToCalendarSourceId` | `UUID` | — |

### `EventExecutionResponse`

| Field | Java type | Validation |
|---|---|---|
| `eventId` | `UUID` | — |
| `finalPlanId` | `UUID` | — |
| `actualPrepStartedAt` | `Instant` | — |
| `actualPrepFinishedAt` | `Instant` | — |
| `actualDepartedAt` | `Instant` | — |
| `actualArrivedAt` | `Instant` | — |
| `arrivalResult` | `ArrivalResult` — enum: `early, on_time, rushed, late, unknown` | — |
| `resultSource` | `String` | — |
| `actualOutdoorMinutes` | `Integer` | — |
| `rushLoadScore` | `Short` | — |
| `delayReasons` | `List<DelayReasonResponse>` | — |

### `EventFeedbackRequest`

| Field | Java type | Validation |
|---|---|---|
| `prepTimingAssessment` | `PrepTimingAssessment` — enum: `too_early, appropriate, too_late, unknown` | NotNull |
| `arrivalResult` | `ArrivalResult` — enum: `early, on_time, rushed, late, unknown` | — |
| `rushAssessment` | `RushAssessment` — enum: `rushed, not_rushed, unknown` | — |

### `EventFeedbackResponse`

| Field | Java type | Validation |
|---|---|---|
| `eventId` | `UUID` | — |
| `prepTimingAssessment` | `PrepTimingAssessment` — enum: `too_early, appropriate, too_late, unknown` | — |
| `arrivalResult` | `ArrivalResult` — enum: `early, on_time, rushed, late, unknown` | — |
| `rushAssessment` | `RushAssessment` — enum: `rushed, not_rushed, unknown` | — |

### `EventResponse`

| Field | Java type | Validation |
|---|---|---|
| `eventId` | `UUID` | — |
| `displayName` | `String` | — |
| `startsAt` | `Instant` | — |
| `endsAt` | `Instant` | — |
| `timezone` | `String` | — |
| `locationState` | `LocationState` — enum: `required_resolved, required_missing, not_required, undecided` | — |
| `destinationName` | `String` | — |
| `destinationAddress` | `String` | — |
| `destinationLat` | `Double` | — |
| `destinationLng` | `Double` | — |
| `meetingUrl` | `String` | — |
| `eventKind` | `String` | — |
| `status` | `EventStatus` — enum: `planned, notified, preparing, enroute, arrived, closed, skipped, cancelled, unresolved` | — |
| `autoManageExcluded` | `boolean` | — |
| `sourceType` | `SourceType` — enum: `internal, external, map_search` | — |
| `calendarSourceId` | `UUID` | — |
| `anchorMode` | `String` | — |
| `route` | `RouteOptionResponse` | — |
| `plan` | `PlanResponse` | — |

### `EventReviewRequest`

| Field | Java type | Validation |
|---|---|---|
| `reviewId` | `UUID` | — |
| `questionType` | `String` | NotBlank |
| `userAnswer` | `String` | NotBlank |

### `EventReviewResponse`

| Field | Java type | Validation |
|---|---|---|
| `eventId` | `UUID` | — |
| `locationState` | `LocationState` — enum: `required_resolved, required_missing, not_required, undecided` | — |
| `reviewClosed` | `boolean` | — |

### `EventUpdateRequest`

| Field | Java type | Validation |
|---|---|---|
| `startsAt` | `Instant` | — |
| `endsAt` | `Instant` | — |
| `locationState` | `LocationState` — enum: `required_resolved, required_missing, not_required, undecided` | — |
| `destinationName` | `String` | — |
| `destinationAddress` | `String` | — |
| `destinationLat` | `Double` | — |
| `destinationLng` | `Double` | — |
| `meetingUrl` | `String` | — |
| `eventKind` | `String` | — |
| `displayLabel` | `String` | — |
| `autoManageExcluded` | `Boolean` | — |
| `anchorMode` | `String` | — |

### `GoogleLoginRequest`

| Field | Java type | Validation |
|---|---|---|
| `idToken` | `String` | NotBlank |

### `LinkProviderRequest`

| Field | Java type | Validation |
|---|---|---|
| `provider` | `String` | NotBlank |
| `providerToken` | `String` | NotBlank |

### `LoginRequest`

| Field | Java type | Validation |
|---|---|---|
| `email` | `String` | NotBlank |
| `password` | `String` | NotBlank |

### `NotificationRespondRequest`

| Field | Java type | Validation |
|---|---|---|
| `reaction` | `String` | NotBlank |

### `NotificationResponse`

| Field | Java type | Validation |
|---|---|---|
| `notificationId` | `UUID` | — |
| `planId` | `UUID` | — |
| `notificationCategory` | `String` | — |
| `notificationType` | `String` | — |
| `slot` | `String` | — |
| `scheduledAt` | `Instant` | — |
| `sentAt` | `Instant` | — |
| `deliveryStatus` | `String` | — |
| `body` | `String` | — |
| `triggerReason` | `String` | — |
| `reaction` | `String` | — |

### `OnboardingProgressRequest`

| Field | Java type | Validation |
|---|---|---|
| `currentStep` | `String` | NotBlank |

### `OnboardingProgressResponse`

| Field | Java type | Validation |
|---|---|---|
| `currentStep` | `String` | — |
| `completed` | `boolean` | — |
| `completedAt` | `Instant` | — |
| `coachmarkSeen` | `boolean` | — |

### `PasswordResetExecuteRequest`

| Field | Java type | Validation |
|---|---|---|
| `token` | `String` | NotBlank |
| `newPassword` | `String` | NotBlank |

### `PasswordResetRequest`

| Field | Java type | Validation |
|---|---|---|
| `email` | `String` | NotBlank; Email |

### `PendingEventReviewResponse`

| Field | Java type | Validation |
|---|---|---|
| `reviewId` | `UUID` | — |
| `eventId` | `UUID` | — |
| `startsAt` | `Instant` | — |
| `questionType` | `String` | — |
| `suggestedValue` | `String` | — |
| `classificationConfidence` | `BigDecimal` | — |
| `askedAt` | `Instant` | — |

### `PermissionResponse`

| Field | Java type | Validation |
|---|---|---|
| `permissionType` | `String` | — |
| `status` | `String` | — |

### `PermissionUpdateRequest`

| Field | Java type | Validation |
|---|---|---|
| `permissions` | `List< PermissionItem>` | NotEmpty |

### `PersonalizationResponse`

| Field | Java type | Validation |
|---|---|---|
| `estimates` | `List<PrepEstimateResponse>` | — |
| `trafficBufferMinutes` | `int` | — |
| `notificationLeadMinutes` | `int` | — |

### `PlaceRequest`

| Field | Java type | Validation |
|---|---|---|
| `placeType` | `PlaceType` — enum: `home, school, work, other` | NotNull |
| `placeName` | `String` | NotBlank |
| `address` | `String` | NotBlank |
| `lat` | `Double` | NotNull |
| `lng` | `Double` | NotNull |
| `isPrimary` | `boolean` | — |

### `PlaceResponse`

| Field | Java type | Validation |
|---|---|---|
| `placeId` | `UUID` | — |
| `placeType` | `PlaceType` — enum: `home, school, work, other` | — |
| `placeName` | `String` | — |
| `address` | `String` | — |
| `lat` | `double` | — |
| `lng` | `double` | — |
| `isPrimary` | `boolean` | — |

### `PlaceUpdateRequest`

| Field | Java type | Validation |
|---|---|---|
| `placeType` | `PlaceType` — enum: `home, school, work, other` | — |
| `placeName` | `String` | — |
| `address` | `String` | — |
| `lat` | `Double` | — |
| `lng` | `Double` | — |
| `isPrimary` | `Boolean` | — |

### `PlanDetailResponse`

| Field | Java type | Validation |
|---|---|---|
| `planId` | `UUID` | — |
| `eventId` | `UUID` | — |
| `revisionNo` | `int` | — |
| `calcVersion` | `String` | — |
| `planStatus` | `String` | — |
| `eventStatus` | `String` | — |
| `feasible` | `boolean` | — |
| `predictionConfidence` | `String` | — |
| `prepStartAt` | `Instant` | — |
| `recommendedDepartAt` | `Instant` | — |
| `targetArriveAt` | `Instant` | — |
| `breakdown` | `Breakdown` | — |
| `reasons` | `List<ReasonItem>` | — |
| `checklist` | `List<ChecklistItem>` | — |
| `wellnessActions` | `List<WellnessActionItem>` | — |
| `wellness` | `WellnessScoreItem` | — |
| `context` | `ContextItem` | — |
| `selectedRouteOptionId` | `UUID` | — |
| `degraded` | `List<String>` | — |

### `PlanPatchRequest`

| Field | Java type | Validation |
|---|---|---|
| `originPlaceId` | `UUID` | — |
| `prepStartAt` | `Instant` | — |

### `PlanRecalculateResponse`

| Field | Java type | Validation |
|---|---|---|
| `changed` | `boolean` | — |
| `plan` | `PlanDetailResponse` | — |

### `PrepItemResolveRequest`

| Field | Java type | Validation |
|---|---|---|
| `completionStatus` | `PrepItemCompletionStatus` — enum: `PENDING, COMPLETED` | NotNull |
| `clientEventId` | `UUID` | — |

### `PrepItemResolveResponse`

| Field | Java type | Validation |
|---|---|---|
| `planPrepItemId` | `UUID` | — |
| `completionStatus` | `String` | — |
| `completedAt` | `Instant` | — |

### `PrepRuleRequest`

| Field | Java type | Validation |
|---|---|---|
| `ruleName` | `String` | NotBlank |
| `ruleCategory` | `RuleCategory` — enum: `supplement, medication, personal_item, routine, general_item` | NotNull |
| `actionType` | `ActionType` — enum: `carry, consume, purchase, timed_routine` | NotNull |
| `ruleTiming` | `RuleTiming` — enum: `pre_departure, post_arrival` | NotNull |
| `defaultMinutes` | `Integer` | — |
| `applyEventKind` | `String` | — |
| `applyTimeBand` | `String` | — |
| `applyPlaceId` | `UUID` | — |
| `applyWeather` | `String` | — |
| `isRequired` | `boolean` | — |
| `isSensitive` | `boolean` | — |
| `fromChip` | `boolean` | — |

### `PrepRuleResponse`

| Field | Java type | Validation |
|---|---|---|
| `prepRuleId` | `UUID` | — |
| `ruleName` | `String` | — |
| `ruleCategory` | `RuleCategory` — enum: `supplement, medication, personal_item, routine, general_item` | — |
| `actionType` | `ActionType` — enum: `carry, consume, purchase, timed_routine` | — |
| `ruleTiming` | `RuleTiming` — enum: `pre_departure, post_arrival` | — |
| `defaultMinutes` | `Integer` | — |
| `applyEventKind` | `String` | — |
| `applyTimeBand` | `String` | — |
| `applyPlaceId` | `UUID` | — |
| `applyWeather` | `String` | — |
| `isRequired` | `boolean` | — |
| `isSensitive` | `boolean` | — |
| `fromChip` | `boolean` | — |

### `PrepRuleUpdateRequest`

| Field | Java type | Validation |
|---|---|---|
| `ruleName` | `String` | — |
| `defaultMinutes` | `Integer` | — |
| `isRequired` | `Boolean` | — |
| `isSensitive` | `Boolean` | — |

### `ProviderResponse`

| Field | Java type | Validation |
|---|---|---|
| `identityId` | `UUID` | — |
| `provider` | `String` | — |
| `linkedAt` | `Instant` | — |

### `PushDeviceResponse`

| Field | Java type | Validation |
|---|---|---|
| `pushDeviceId` | `UUID` | — |
| `installationId` | `UUID` | — |
| `platform` | `String` | — |
| `lastSeenAt` | `Instant` | — |

### `RecalculateRequest`

| Field | Java type | Validation |
|---|---|---|
| `reason` | `String` | — |

### `RecentDestinationResponse`

| Field | Java type | Validation |
|---|---|---|
| `recentDestinationId` | `UUID` | — |
| `placeName` | `String` | — |
| `address` | `String` | — |
| `lat` | `BigDecimal` | — |
| `lng` | `BigDecimal` | — |
| `bookmarked` | `boolean` | — |
| `lastUsedAt` | `Instant` | — |

### `RegisterPushDeviceRequest`

| Field | Java type | Validation |
|---|---|---|
| `installationId` | `UUID` | NotNull |
| `currentToken` | `String` | NotBlank |
| `platform` | `Platform` — enum: `IOS, ANDROID, WEB` | NotNull |

### `ResendVerificationRequest`

| Field | Java type | Validation |
|---|---|---|
| `email` | `String` | NotBlank; Email |

### `RouteOptionResponse`

| Field | Java type | Validation |
|---|---|---|
| `routeOptionId` | `UUID` | — |
| `routeRank` | `int` | — |
| `routeType` | `String` | — |
| `totalMinutes` | `int` | — |
| `walkMinutes` | `int` | — |
| `transferCount` | `int` | — |
| `departAt` | `Instant` | — |
| `arriveAt` | `Instant` | — |
| `provider` | `String` | — |
| `legs` | `List<Leg>` | — |
| `degraded` | `List<String>` | — |

### `RouteSearchResponse`

| Field | Java type | Validation |
|---|---|---|
| `routeOptionId` | `UUID` | — |
| `routeRank` | `int` | — |
| `routeType` | `String` | — |
| `totalMinutes` | `int` | — |
| `walkMinutes` | `int` | — |
| `transferCount` | `int` | — |
| `departAt` | `Instant` | — |
| `arriveAt` | `Instant` | — |
| `provider` | `String` | — |
| `legs` | `List<Leg>` | — |
| `degraded` | `List<String>` | — |

### `RouteSelectRequest`

| Field | Java type | Validation |
|---|---|---|
| `routeOptionId` | `UUID` | NotNull |

### `SessionResponse`

| Field | Java type | Validation |
|---|---|---|
| `refreshTokenId` | `UUID` | — |
| `issuedAt` | `Instant` | — |
| `isCurrent` | `boolean` | — |

### `SettingsRequest`

| Field | Java type | Validation |
|---|---|---|
| `initialPrepMinutes` | `Integer` | Min(0) |
| `arrivalBufferMinutes` | `Integer` | NotNull; Min(0) |
| `notificationSensitivity` | `String` | NotBlank |
| `personalizationEnabled` | `Boolean` | NotNull |
| `autoManageEnabled` | `Boolean` | NotNull |
| `wellnessEventEnabled` | `Boolean` | NotNull |
| `lockscreenHideSensitive` | `Boolean` | NotNull |

### `SettingsResponse`

| Field | Java type | Validation |
|---|---|---|
| `initialPrepMinutes` | `Integer` | — |
| `arrivalBufferMinutes` | `int` | — |
| `notificationSensitivity` | `String` | — |
| `personalizationEnabled` | `boolean` | — |
| `autoManageEnabled` | `boolean` | — |
| `wellnessEventEnabled` | `boolean` | — |
| `lockscreenHideSensitive` | `boolean` | — |

### `SignupRequest`

| Field | Java type | Validation |
|---|---|---|
| `name` | `String` | — |
| `nickname` | `String` | — |
| `email` | `String` | NotBlank; Email |
| `password` | `String` | NotBlank; Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.") |
| `timezone` | `String` | — |
| `installationId` | `UUID` | — |
| `verificationTicket` | `String` | — |
| `consents` | `Map<String, Boolean>` | — |

### `SignupResponse`

| Field | Java type | Validation |
|---|---|---|
| `id` | `UUID` | — |
| `email` | `String` | — |
| `emailVerified` | `boolean` | — |
| `verificationSent` | `boolean` | — |

### `TodayPlanResponse`

| Field | Java type | Validation |
|---|---|---|
| `serverNow` | `Instant` | — |
| `date` | `LocalDate` | — |
| `homeState` | `String` | — |
| `cards` | `List<Card>` | — |
| `wrapSummary` | `DailySummaryResponse` | — |
| `degraded` | `List<String>` | — |

### `TokenResponse`

| Field | Java type | Validation |
|---|---|---|
| `accessToken` | `String` | — |
| `refreshToken` | `String` | — |
| `expiresIn` | `long` | — |
| `user` | `UserInfo` | — |
| `consentRequired` | `List<String>` | — |
| `onboarding` | `OnboardingProgressResponse` | — |

### `VerifyEmailRequest`

| Field | Java type | Validation |
|---|---|---|
| `token` | `String` | NotBlank |

### `WeeklySummaryResponse`

| Field | Java type | Validation |
|---|---|---|
| `weekStart` | `LocalDate` | — |
| `weekEnd` | `LocalDate` | — |
| `managedEventCount` | `int` | — |
| `onTimeRate` | `Double` | — |
| `onTimeSampleCount` | `int` | — |
| `averageSlackMinutes` | `Integer` | — |
| `averageSlackSampleCount` | `int` | — |
| `prepAccuracy` | `List<PrepAccuracyPoint>` | — |
| `wellnessCompletionRate` | `Double` | — |
| `wellnessProposedCount` | `int` | — |
| `wellnessCompletedCount` | `int` | — |
| `outdoorMinutes` | `int` | — |
| `outdoorSampleCount` | `int` | — |
| `outdoorSource` | `String` | — |

### `WellnessActionResolveRequest`

| Field | Java type | Validation |
|---|---|---|
| `completionStatus` | `WellnessActionCompletionStatus` — enum: `PROPOSED, COMPLETED, DISMISSED` | NotNull |
| `clientEventId` | `UUID` | — |

### `WellnessActionResolveResponse`

| Field | Java type | Validation |
|---|---|---|
| `wellnessActionId` | `UUID` | — |
| `completionStatus` | `String` | — |
| `respondedAt` | `Instant` | — |

### `WellnessPrefResponse`

| Field | Java type | Validation |
|---|---|---|
| `wellnessTopic` | `WellnessTopic` — enum: `uv, pm, temp, rain, hydration` | — |
| `isEnabled` | `boolean` | — |
| `remindIntervalMinutes` | `Integer` | — |
| `dailyEventCap` | `int` | — |

### `WellnessPrefsRequest`

| Field | Java type | Validation |
|---|---|---|
| `prefs` | `List<Item>` | NotEmpty |

## 5. 유지보수

- Controller mapping, DTO, validation, enum serialization 변경 시 generator를 실행하고 생성 diff를 같은 PR에 포함한다.
- 이 문서는 runtime Swagger 대체물이 아니다. 현재 springdoc dependency가 없고 deployed Swagger endpoint는 auth boundary 뒤에 있으므로 controller/DTO source를 canonical contract로 사용한다.
- Enum wire value는 `@JsonValue` 유무로 결정된다. `@JsonValue` enum은 lower-snake-case, 없는 enum은 `name()` 그대로(대문자)다. 표에 대문자 enum이 보이면 의도한 것인지 확인한다.
- 같은 이름의 type이 여러 package에 있으면(`ActionType` 등) 참조한 DTO의 import로 해석한다. 새 동명 type을 추가할 때 이 해석이 맞는지 확인한다.
- FE 정적 `ApiClient` literal route 대조는 FE `origin/dev` 기준 64개 호출 전부 method/path match, missing 0이었다. 이후 FE/BE 변경 시 같은 대조를 갱신한다.
