package com.hq.backend.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdsayRouteProviderTest {

    // ODsay searchPubTransPathT의 도시내 대중교통 경로 응답을 축약한 고정 fixture.
    private static final String SUCCESS_FIXTURE = """
            {
              "result": {
                "path": [
                  {
                    "pathType": 3,
                    "info": {
                      "totalTime": 48,
                      "busTransitCount": 1,
                      "subwayTransitCount": 1,
                      "mapObj": "0:0@100:1:1:3"
                    },
                    "subPath": [
                      {"trafficType": 3, "distance": 350, "sectionTime": 5},
                      {"trafficType": 2, "distance": 9100, "sectionTime": 30},
                      {"trafficType": 1, "distance": 7200, "sectionTime": 13}
                    ]
                  }
                ]
              }
            }
            """;

    @Test
    void search_maps_official_route_fields_and_converts_minutes_to_seconds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andExpect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/v1/api/searchPubTransPathT");
                    assertThat(request.getURI().getQuery()).contains(
                            "SX=126.978", "SY=37.5665", "EX=127.0276", "EY=37.4979", "apiKey=test-key");
                })
                .andRespond(withSuccess(SUCCESS_FIXTURE, MediaType.APPLICATION_JSON));

        OdsayRouteProvider provider = provider(builder.build());
        Instant at = Instant.parse("2026-08-17T00:00:00Z");

        List<RouteOption> options = provider.search(
                new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276), "arriveBy", at);

        assertThat(options).hasSize(1);
        RouteOption option = options.getFirst();
        assertThat(option.provider()).isEqualTo("odsay");
        assertThat(option.rank()).isEqualTo("fastest");
        assertThat(option.totalSec()).isEqualTo(48 * 60);
        assertThat(option.walkSec()).isEqualTo(5 * 60);
        assertThat(option.outdoorSec()).isEqualTo(5 * 60);
        assertThat(option.transfers()).isEqualTo(2);
        assertThat(option.legs()).containsExactly(
                new Leg("WALK", 5 * 60, 350),
                new Leg("BUS", 30 * 60, 9100),
                new Leg("SUBWAY", 13 * 60, 7200));
        assertThat(option.departAt()).isEqualTo(at);
        assertThat(option.etaAt()).isEqualTo(at.plusSeconds(48 * 60));
        server.verify();
    }

    @Test
    void search_excludes_short_walks_adjacent_to_subway_from_outdoor_exposure() {
        String fixture = """
                {
                  "result": {
                    "path": [
                      {
                        "info": {"totalTime": 20, "busTransitCount": 1, "subwayTransitCount": 1},
                        "subPath": [
                          {"trafficType": 1, "distance": 7200, "sectionTime": 13},
                          {"trafficType": 3, "distance": 150, "sectionTime": 2},
                          {"trafficType": 2, "distance": 1800, "sectionTime": 5}
                        ]
                      },
                      {
                        "info": {"totalTime": 25, "busTransitCount": 0, "subwayTransitCount": 1},
                        "subPath": [
                          {"trafficType": 3, "distance": 300, "sectionTime": 4},
                          {"trafficType": 3, "distance": 150, "sectionTime": 2},
                          {"trafficType": 1, "distance": 7200, "sectionTime": 13}
                        ]
                      }
                    ]
                  }
                }
                """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        List<RouteOption> options = provider(builder.build()).search(
                new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276), "arriveBy", Instant.now());

        assertThat(options).hasSize(2);
        assertThat(options).extracting(RouteOption::walkSec).containsExactly(2 * 60, 6 * 60);
        assertThat(options).extracting(RouteOption::outdoorSec).containsExactly(0, 4 * 60);
        server.verify();
    }

    @Test
    void search_reduces_overlapping_rank_winners_instead_of_emitting_duplicate_fastest() {
        String fixture = """
                {
                  "result": {
                    "path": [
                      {
                        "info": {"totalTime": 10, "busTransitCount": 1, "subwayTransitCount": 0},
                        "subPath": [{"trafficType": 3, "distance": 500, "sectionTime": 5}]
                      },
                      {
                        "info": {"totalTime": 20, "busTransitCount": 0, "subwayTransitCount": 0},
                        "subPath": [{"trafficType": 3, "distance": 100, "sectionTime": 1}]
                      },
                      {
                        "info": {"totalTime": 30, "busTransitCount": 2, "subwayTransitCount": 0},
                        "subPath": [{"trafficType": 3, "distance": 200, "sectionTime": 2}]
                      }
                    ]
                  }
                }
                """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        List<RouteOption> options = provider(builder.build()).search(
                new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276), "arriveBy", Instant.now());

        assertThat(options).extracting(RouteOption::rank).containsExactly("fastest", "least_walk");
        assertThat(options).extracting(RouteOption::rank).doesNotHaveDuplicates();
        server.verify();
    }

    @Test
    void search_returns_empty_when_odsay_reports_no_routes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\": {\"path\": []}}", MediaType.APPLICATION_JSON));

        List<RouteOption> options = provider(builder.build()).search(
                new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276), "arriveBy", Instant.now());

        assertThat(options).isEmpty();
        server.verify();
    }

    @Test
    void search_uses_stub_fallback_when_odsay_http_call_fails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withServerError());
        Instant at = Instant.parse("2026-08-17T00:00:00Z");

        List<RouteOption> options = provider(builder.build()).search(
                new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276), "arriveBy", at);

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.provider()).isEqualTo("stub");
            assertThat(option.departAt()).isEqualTo(at);
        });
        server.verify();
    }

    @Test
    void search_uses_stub_fallback_when_odsay_returns_error_payload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"error\": {\"code\": -99, \"msg\": \"no route\"}}", MediaType.APPLICATION_JSON));

        List<RouteOption> options = provider(builder.build()).search(
                new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276), "arriveBy", Instant.now());

        assertThat(options).singleElement().extracting(RouteOption::provider).isEqualTo("stub");
        server.verify();
    }

    @Test
    void apiKey_reserved_characters_are_percent_encoded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .contains("apiKey=a%2Bb%2Fc%3D%3D"))
                .andRespond(withSuccess(SUCCESS_FIXTURE, MediaType.APPLICATION_JSON));

        OdsayRouteProvider provider = new OdsayRouteProvider(
                builder.build(),
                new StubRouteProvider(),
                "a+b/c==",
                "https://api.odsay.com/v1/api/searchPubTransPathT");

        provider.search(new GeoPoint(37.5665, 126.9780), new GeoPoint(37.4979, 127.0276),
                "arriveBy", Instant.parse("2026-08-17T00:00:00Z"));

        server.verify();
    }

    private OdsayRouteProvider provider(RestClient restClient) {
        return new OdsayRouteProvider(
                restClient,
                new StubRouteProvider(),
                "test-key",
                "https://api.odsay.com/v1/api/searchPubTransPathT");
    }
}
