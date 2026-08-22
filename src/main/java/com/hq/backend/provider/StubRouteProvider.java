package com.hq.backend.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

// ponytail: ODsay 실 연동(M1) 전까지 쓰는 고정값 스텁. 입력 무관하게 도보 위주 경로 1건을 반환한다.
// 실 구현으로 교체할 때는 이 클래스를 지우고 OdsayRouteProvider로 빈을 바꾸면 끝.
@Component
public class StubRouteProvider implements RouteProvider {

    private static final int TOTAL_SEC = 1200;
    private static final int WALK_SEC = 420;
    private static final int OUTDOOR_SEC = 420;

    @Override
    public List<RouteOption> search(GeoPoint origin, GeoPoint dest, String anchor, Instant at) {
        List<Leg> legs = List.of(
                new Leg("WALK", 300, 350),
                new Leg("BUS", 780, 4200),
                new Leg("WALK", 120, 150)
        );
        Instant departAt = "arrive_by".equals(anchor) ? at.minusSeconds(TOTAL_SEC) : at;
        Instant arriveAt = "arrive_by".equals(anchor) ? at : at.plusSeconds(TOTAL_SEC);
        RouteOption option = new RouteOption(
                UUID.randomUUID().toString(),
                "fastest",
                TOTAL_SEC,
                WALK_SEC,
                1,
                OUTDOOR_SEC,
                legs,
                departAt,
                arriveAt,
                "stub",
                UUID.randomUUID().toString()
        );
        return List.of(option);
    }
}
