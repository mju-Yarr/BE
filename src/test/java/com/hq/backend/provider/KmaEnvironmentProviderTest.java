package com.hq.backend.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KmaEnvironmentProviderTest {

    // 실제 getVilageFcst 응답 형태(response.body.items.item[])를 축약한 고정 픽스처.
    private static final String FIXTURE = """
            {
              "response": {
                "body": {
                  "items": {
                    "item": [
                      {"category": "TMP", "fcstValue": "22"},
                      {"category": "POP", "fcstValue": "30"}
                    ]
                  }
                }
              }
            }
            """;

    @Test
    void fetchParsesTempAndPrecipitationFromFixture() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess(FIXTURE, MediaType.APPLICATION_JSON));

        KmaEnvironmentProvider provider = new KmaEnvironmentProvider(builder.build());
        setPrivateField(provider, "serviceKey", "test-key");
        setPrivateField(provider, "forecastUrl", "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst");

        EnvironmentSnapshot snapshot = provider.fetch(new GeoPoint(37.5665, 126.9780), Instant.now());

        assertThat(snapshot.tempC()).isEqualTo(22.0);
        assertThat(snapshot.precipitationProb()).isEqualTo(30);
        server.verify();
    }

    @Test
    void serviceKeyReservedCharactersArePercentEncoded() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .contains("serviceKey=a%2Bb%2Fc%3D%3D"))
                .andRespond(withSuccess(FIXTURE, MediaType.APPLICATION_JSON));

        KmaEnvironmentProvider provider = new KmaEnvironmentProvider(builder.build());
        setPrivateField(provider, "serviceKey", "a+b/c==");
        setPrivateField(provider, "forecastUrl", "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst");

        provider.fetch(new GeoPoint(37.5665, 126.9780), Instant.now());

        server.verify();
    }

    private void setPrivateField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
