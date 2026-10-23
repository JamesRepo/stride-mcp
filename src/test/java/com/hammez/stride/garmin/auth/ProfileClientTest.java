package com.hammez.stride.garmin.auth;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ProfileClientTest {

    private final FakeGarmin garmin = new FakeGarmin();
    private final ProfileClient client = new ProfileClient(garmin.httpClient(), garmin.config(), JsonMapper.builder().build());

    @AfterEach
    void stop() {
        garmin.close();
    }

    @Test
    void readsTheProfileWithNativeHeaders() {
        garmin.respond(PROFILE_PATH, 200, GarminAuthTest.PROFILE);

        GarminProfile profile = client.fetch("access-1");

        assertThat(profile).isEqualTo(new GarminProfile("Jo Runner", "jorunner", "0b2a6f6e-0000-0000-0000-000000000000"));
        FakeGarmin.Request request = garmin.requests().getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.header("Authorization")).isEqualTo("Bearer access-1");
        assertThat(request.header("X-Garmin-User-Agent")).startsWith("com.garmin.android.apps.connectmobile/");
    }

    @Test
    void friendlyNameFallsBackFromFullNameToUserNameToDisplayName() {
        assertThat(new GarminProfile("Jo Runner", "jorunner", "uuid").friendlyName()).isEqualTo("Jo Runner");
        assertThat(new GarminProfile(" ", "jorunner", "uuid").friendlyName()).isEqualTo("jorunner");
        assertThat(new GarminProfile(null, null, "uuid").friendlyName()).isEqualTo("uuid");
        assertThat(new GarminProfile(null, null, null).friendlyName()).isEqualTo("(unknown)");
    }

    @Test
    void missingFieldsAreNull() {
        garmin.respond(PROFILE_PATH, 200, "{\"displayName\":\"uuid\",\"fullName\":null}");

        assertThat(client.fetch("access-1")).isEqualTo(new GarminProfile(null, null, "uuid"));
    }

    @Test
    void rejectedTokenMeansSessionExpired() {
        garmin.respond(PROFILE_PATH, 401, "");

        assertThat(catchThrowable(() -> client.fetch("access-1"))).isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void forbidden() {
        garmin.respond(PROFILE_PATH, 403, "");

        assertThat(catchThrowable(() -> client.fetch("access-1")))
                .isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("HTTP 403");
    }

    @Test
    void rateLimited() {
        garmin.respond(PROFILE_PATH, 429, "");

        assertThat(catchThrowable(() -> client.fetch("access-1"))).isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests()).hasSize(1);
    }
}
