package com.hq.backend.event.classification;

public enum AiReviewOutcome {
    CREATED,
    DUPLICATE,
    STALE,
    ANSWERED_ONLINE,
    ANSWERED_OFFLINE,
    CLOSED_BY_USER_PATCH,
    // 사용자가 "잘 모르겠어요"로 닫은 경우. 답변은 했지만 분류는 확정되지 않았다.
    DECLINED_BY_USER
}
