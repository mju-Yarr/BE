package com.hq.backend.event;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SourceType {
    INTERNAL, EXTERNAL, MAP_SEARCH;

    // LocationState와 같은 이유로 lower_snake로 내보낸다. @JsonValue 없이 name()이
    // 그대로 나가면 EventResponse.sourceType이 대문자가 되어 lower_snake를 기대하는
    // 클라이언트 파싱이 깨진다. 역직렬화는 accept-case-insensitive-enums 설정이
    // 기존 대문자 요청도 계속 받아준다.
    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
