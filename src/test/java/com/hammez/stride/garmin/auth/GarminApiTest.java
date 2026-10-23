package com.hammez.stride.garmin.auth;

import static com.hammez.stride.garmin.auth.FakeGarmin.TOKEN_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.jwt;
import static com.hammez.stride.garmin.auth.FakeGarmin.tokenReply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** Signed-in data requests against a fake Garmin. */
class GarminApiTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String CLIENT = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2";
    private static final String PATH = "/activity-service/activity/20123456789";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private final String access = jwt(NOW.getEpochSecond() + 3600, CLIENT);
    private final String fresh = jwt(NOW.getEpochSecond() + 75_600, CLIENT);
    private TokenStore store;
    private GarminApi api;

    @BeforeEach
    void setUp() {
        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GarminAuthConfig config = garmin.config();
        store = new TokenStore(temp.resolve("garmin_tokens.json"), json);
        var tokenClient = new DiTokenClient(garmin.httpClient(), config, json, clock);
        api = new GarminApi(garmin.httpClient(), config, new TokenManager(store, tokenClient, config, clock));
    }

    @AfterEach
    void stop() {
        garmin.close();
    }

    @Test
    void returnsTheBodyAndSendsTheSessionAndNativeHeaders() {
        saveTokens(access);
        garmin.respond(PATH, 200, "{\"activityId\":20123456789}");

        assertThat(api.get(PATH, Map.of())).isEqualTo("{\"activityId\":20123456789}");

        assertThat(garmin.requests()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + access);
            assertThat(r.header("Accept")).isEqualTo("application/json");
            assertThat(r.header("X-Garmin-User-Agent")).startsWith("com.garmin.android.apps.connectmobile/");
        });
    }

    @Test
    void sendsQueryParametersEncodedAndSorted() {
        saveTokens(access);
        String path = "/activitylist-service/activities/search/activities";
        garmin.respond(path, 200, "[]");

        api.get(path, Map.of("startDate", "2026-10-17", "endDate", "2026-10-23", "activityType", "trail running",
                "offset", "+01:00"));

        FakeGarmin.Request request = garmin.requests().getFirst();
        assertThat(request.query()).isEqualTo(
                "activityType=trail%20running&endDate=2026-10-23&offset=%2B01%3A00&startDate=2026-10-17");
        assertThat(request.queryParams()).containsEntry("activityType", "trail running").containsEntry("offset", "+01:00");
    }

    @Test
    void noContentIsAnEmptyBody() {
        saveTokens(access);
        garmin.respond(PATH, 204, "");

        assertThat(api.get(PATH, Map.of())).isEmpty();
    }

    @Test
    void refreshesFirstWhenTheAccessTokenIsAboutToRunOut() {
        store.save(new GarminTokens(access, "refresh-1", CLIENT, NOW.plusSeconds(60), NOW.plusSeconds(86_400)));
        garmin.respond(TOKEN_PATH, 200, tokenReply(fresh, "refresh-2"))
                .respond(PATH, 200, "{}");

        api.get(PATH, Map.of());

        assertThat(garmin.requests(PATH)).singleElement()
                .satisfies(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + fresh));
    }

    @Test
    void refreshesOnceAndRetriesOnceWhenGarminTurnsDownTheToken() {
        saveTokens(access);
        garmin.respond(PATH, 401, "")
                .respond(TOKEN_PATH, 200, tokenReply(fresh, "refresh-2"))
                .respond(PATH, 200, "{\"ok\":true}");

        assertThat(api.get(PATH, Map.of())).isEqualTo("{\"ok\":true}");

        assertThat(garmin.requests(PATH)).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer " + access, "Bearer " + fresh);
        assertThat(store.load()).hasValueSatisfying(t -> assertThat(t.refreshToken()).isEqualTo("refresh-2"));
    }

    @Test
    void neverRefreshesTwiceForOneRequest() {
        // The saved token is about to run out, so it's refreshed first. Garmin then turns the new one down.
        store.save(new GarminTokens(access, "refresh-1", CLIENT, NOW.plusSeconds(60), NOW.plusSeconds(86_400)));
        garmin.respond(TOKEN_PATH, 200, tokenReply(fresh, "refresh-2"))
                .respond(PATH, 401, "");

        assertThatThrownBy(() -> api.get(PATH, Map.of())).isInstanceOf(SessionExpiredException.class);
        assertThat(garmin.requests(TOKEN_PATH)).hasSize(1);
        assertThat(garmin.requests(PATH)).hasSize(1);
    }

    @Test
    void anErrorAfterTheRetryHidesBothAccessTokens() {
        // Not JWTs, so only the known-secret list can hide them.
        store.save(new GarminTokens("old-opaque-token", "refresh-1", CLIENT, NOW.plusSeconds(3600),
                NOW.plusSeconds(86_400)));
        garmin.respond(PATH, 401, "")
                .respond(TOKEN_PATH, 200, tokenReply(fresh, "refresh-2"))
                .respond(PATH, 500, "{\"seen\":\"old-opaque-token\",\"now\":\"" + fresh + "\"}");

        assertThat(catchThrowable(() -> api.get(PATH, Map.of())))
                .isExactlyInstanceOf(GarminException.class).hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("old-opaque-token").hasMessageNotContaining(fresh);
    }

    @Test
    void aSecondRejectionMeansTheSessionHasExpired() {
        saveTokens(access);
        garmin.respond(PATH, 401, "")
                .respond(TOKEN_PATH, 200, tokenReply(fresh, "refresh-2"))
                .respond(PATH, 401, "");

        assertThatThrownBy(() -> api.get(PATH, Map.of())).isInstanceOf(SessionExpiredException.class);
        assertThat(garmin.requests(TOKEN_PATH)).hasSize(1);
        assertThat(garmin.requests(PATH)).hasSize(2);
    }

    @Test
    void aRejectedRefreshStopsWithoutRetrying() {
        saveTokens(access);
        garmin.respond(PATH, 401, "")
                .respond(TOKEN_PATH, 400, "{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> api.get(PATH, Map.of())).isInstanceOf(SessionExpiredException.class);
        assertThat(garmin.requests(PATH)).hasSize(1);
    }

    @Test
    void notLoggedInNeverCallsGarmin() {
        assertThatThrownBy(() -> api.get(PATH, Map.of())).isInstanceOf(NotLoggedInException.class);
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void notFound() {
        saveTokens(access);
        garmin.respond(PATH, 404, "{\"message\":\"Not Found\"}");

        assertThat(catchThrowable(() -> api.get(PATH, Map.of())))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("HTTP 404").hasMessageContaining(PATH);
        assertThat(garmin.requests(TOKEN_PATH)).isEmpty();
    }

    @Test
    void forbiddenStopsWithoutRefreshing() {
        saveTokens(access);
        garmin.respond(PATH, 403, "");

        assertThat(catchThrowable(() -> api.get(PATH, Map.of())))
                .isExactlyInstanceOf(GarminException.class).hasMessageContaining("HTTP 403");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void rateLimitedStopsAtOnce() {
        saveTokens(access);
        garmin.respond(PATH, 429, "");

        assertThat(catchThrowable(() -> api.get(PATH, Map.of()))).isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void serverErrorShowsARedactedSnippet() {
        saveTokens(access);
        garmin.respond(PATH, 503, "{\"error\":\"down\",\"echo\":\"Bearer " + access + "\"}");

        Throwable thrown = catchThrowable(() -> api.get(PATH, Map.of()));

        assertThat(thrown).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("HTTP 503").hasMessageContaining("down")
                .hasMessageNotContaining(access).hasMessageNotContaining("eyJ");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void noConnectionIsAClearError() {
        saveTokens(access);
        garmin.close();

        assertThat(catchThrowable(() -> api.get(PATH, Map.of())))
                .isExactlyInstanceOf(GarminException.class).hasMessageContaining("Couldn't reach Garmin");
    }

    @Test
    void refusesPathsThatCouldEscape() {
        saveTokens(access);

        for (String path : new String[] {"activity", "/a/../b", "/a/%2e%2e/b", "/a?x=1", "/a#b", "/a\\b", "/a/%zz",
                "/daily/" + GarminApi.segment("x/..")}) {
            assertThatThrownBy(() -> api.get(path, Map.of())).as(path)
                    .isExactlyInstanceOf(GarminException.class).hasMessageContaining("won't use");
        }
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void segmentEncodesEverythingThatCouldChangeThePath() {
        assertThat(GarminApi.segment("0b2a6f6e-0000")).isEqualTo("0b2a6f6e-0000");
        assertThat(GarminApi.segment("a b/../c?d#e")).isEqualTo("a%20b%2F..%2Fc%3Fd%23e");
    }

    private void saveTokens(String accessToken) {
        store.save(new GarminTokens(accessToken, "refresh-1", CLIENT, NOW.plusSeconds(3600), NOW.plusSeconds(86_400)));
    }
}
