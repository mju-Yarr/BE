package com.hq.backend.bootstrap;

import com.hq.backend.bootstrap.dto.BootstrapResponse;
import com.hq.backend.bootstrap.dto.EngineConfigSummary;
import com.hq.backend.bootstrap.dto.PlaceSummary;
import com.hq.backend.bootstrap.dto.SettingsSummary;
import com.hq.backend.bootstrap.dto.UserSummary;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.onboarding.OnboardingService;
import com.hq.backend.onboarding.UserOnboardingRepository;
import com.hq.backend.onboarding.dto.OnboardingProgressResponse;
import com.hq.backend.permission.UserPermissionRepository;
import com.hq.backend.permission.dto.PermissionResponse;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.plan.TodayPlanService;
import com.hq.backend.preprule.UserPrepRuleRepository;
import com.hq.backend.preprule.dto.PrepRuleResponse;
import com.hq.backend.setting.UserSettingRepository;
import com.hq.backend.user.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BootstrapService {
    private static final SettingsSummary DEFAULT_SETTINGS = new SettingsSummary(null, 10, "normal", false, true);
    private static final EngineConfigSummary DEFAULT_ENGINE_CONFIG = new EngineConfigSummary("2.1.0", "w1");

    private final UserPlaceRepository userPlaceRepository;
    private final PlaceCoordinateCodec placeCoordinateCodec;
    private final UserSettingRepository userSettingRepository;
    private final UserRepository userRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final UserOnboardingRepository userOnboardingRepository;
    private final UserPrepRuleRepository userPrepRuleRepository;
    private final TodayPlanService todayPlanService;

    @Transactional
    public BootstrapResponse bootstrap(UUID userId) {
        UserSummary user = userRepository.findById(userId)
                .map(found -> new UserSummary(found.getUserId().toString(), found.getNickname(),
                        found.getTimezone(), found.getAccountStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        SettingsSummary settings = userSettingRepository.findById(userId)
                .map(s -> new SettingsSummary(s.getInitialPrepMinutes(), s.getArrivalBufferMinutes(),
                        s.getNotificationSensitivity(), s.isWellnessEventEnabled(), s.isLockscreenHideSensitive()))
                .orElse(DEFAULT_SETTINGS);
        var places = userPlaceRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(place -> new PlaceSummary(place.getPlaceId(), place.getPlaceType(), place.getPlaceName(),
                        place.getAddress(), placeCoordinateCodec.decode(place.getLatEnc()),
                        placeCoordinateCodec.decode(place.getLngEnc()), place.isPrimary())).toList();
        var permissions = userPermissionRepository.findByIdUserId(userId).stream().map(PermissionResponse::from).toList();
        var onboarding = userOnboardingRepository.findById(userId).map(OnboardingProgressResponse::from)
                .orElseGet(() -> new OnboardingProgressResponse(OnboardingService.FIRST_STEP, false, null, false));
        var prepItems = userPrepRuleRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId)
                .stream().map(PrepRuleResponse::from).toList();
        return new BootstrapResponse(user, settings, new BootstrapResponse.PermissionsAndOnboarding(onboarding),
                permissions, places, prepItems, todayPlanService.getToday(userId), DEFAULT_ENGINE_CONFIG);
    }
}
