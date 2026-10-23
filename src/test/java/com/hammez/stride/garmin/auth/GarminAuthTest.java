package com.hammez.stride.garmin.auth;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.TOKEN_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.jwt;
import static com.hammez.stride.garmin.auth.FakeGarmin.tokenReply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** The whole login and whoami flow against a fake Garmin. */
class GarminAuthTest {

    static final String PROFILE = """
            {"id":12345678,"profileId":12345678,"garminGUID":"0b2a6f6e-0000-0000-0000-000000000000",
             "displayName":"0b2a6f6e-0000-0000-0000-000000000000","fullName":"Jo Runner","userName":"jorunner",
             "profileImageUrlLarge":null,"location":"Somewhere","favoriteActivityTypes":["running"]}""";

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String TICKET = "ST-0000001-secretticket-cas";
    private static final String CLIENT = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private TokenStore store;
    private GarminAuth auth;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GarminAuthConfig config = garmin.config();
        store = new TokenStore(temp.resolve("garmin_tokens.json"), json);
        var tokenClient = new DiTokenClient(garmin.httpClient(), config, json, clock);
        var api = new GarminApi(garmin.httpClient(), config, new TokenManager(store, tokenClient, config, clock));
        auth = new GarminAuth(new BrowserLogin(config, url -> true), tokenClient,
                new ProfileClient(api, config, json), store);
    }

    @AfterEach
    void stop() {
        garmin.close();
    }

    @Test
    void loginSavesTokensAfterCheckingTheProfile() {
        String access = jwt(NOW.getEpochSecond() + 75_600, CLIENT);
        garmin.respond(TOKEN_PATH, 200, tokenReply(access, "refresh-1"))
                .respond(PROFILE_PATH, 200, PROFILE);

        GarminAuth.WhoAmI me = login("https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET);

        assertThat(me.profile().friendlyName()).isEqualTo("Jo Runner");
        assertThat(me.sessionExpiresAt()).isEqualTo(NOW.plusSeconds(2_592_000));
        assertThat(store.load()).hasValueSatisfying(t -> {
            assertThat(t.accessToken()).isEqualTo(access);
            assertThat(t.refreshToken()).isEqualTo("refresh-1");
        });
        assertThat(garmin.requests(PROFILE_PATH)).singleElement()
                .satisfies(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + access));
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain(TICKET).doesNotContain(access);
    }

    @Test
    void loginSavesNothingIfTheProfileCheckFails() {
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-1"))
                .respond(PROFILE_PATH, 500, "oops");

        assertThatThrownBy(() -> login(TICKET)).isInstanceOf(GarminException.class).hasMessageContaining("HTTP 500");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void loginSavesNothingIfTheProfileReplyIsEmpty() {
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-1"))
                .respond(PROFILE_PATH, 204, "");

        assertThatThrownBy(() -> login(TICKET)).isInstanceOf(GarminException.class).hasMessageContaining("wasn't a profile");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void loginSavesNothingIfTheProfileReplyIsntJson() {
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-1"))
                .respond(PROFILE_PATH, 200, "<html>Just a moment…</html>");

        assertThatThrownBy(() -> login(TICKET)).isInstanceOf(GarminException.class).hasMessageContaining("wasn't a profile");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void whoamiFailsIfTheProfileReplyIsEmpty() {
        store.save(new GarminTokens(jwt(NOW.getEpochSecond() + 3600, CLIENT), "refresh-1", CLIENT,
                NOW.plusSeconds(3600), NOW.plusSeconds(86_400)));
        garmin.respond(PROFILE_PATH, 204, "");

        assertThatThrownBy(() -> auth.whoami()).isInstanceOf(GarminException.class).hasMessageContaining("wasn't a profile");
    }

    @Test
    void loginWithABadPasteNeverCallsGarmin() {
        assertThatThrownBy(() -> login("https://mobile.integration.garmin.com/gcm/ios"))
                .isInstanceOf(NoTicketFoundException.class);
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void loginWithARejectedTicketSavesNothing() {
        garmin.respond(TOKEN_PATH, 400, "{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> login(TICKET)).isInstanceOf(TicketRejectedException.class);
        assertThat(store.load()).isEmpty();
    }

    @Test
    void whoamiUsesSavedTokens() {
        String access = jwt(NOW.getEpochSecond() + 3600, CLIENT);
        store.save(new GarminTokens(access, "refresh-1", CLIENT, NOW.plusSeconds(3600), NOW.plusSeconds(86_400)));
        garmin.respond(PROFILE_PATH, 200, PROFILE);

        GarminAuth.WhoAmI me = auth.whoami();

        assertThat(me.profile().friendlyName()).isEqualTo("Jo Runner");
        assertThat(me.accessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(garmin.requests(TOKEN_PATH)).isEmpty();
    }

    @Test
    void whoamiWhenNotLoggedIn() {
        assertThatThrownBy(() -> auth.whoami()).isInstanceOf(NotLoggedInException.class);
    }

    @Test
    void logoutDeletesTheFile() {
        store.save(new GarminTokens("a", "r", CLIENT, NOW, null));

        assertThat(auth.logout()).isTrue();
        assertThat(store.load()).isEmpty();
        assertThat(auth.logout()).isFalse();
    }

    @Test
    void whoamiRefreshesOnceWhenGarminRejectsAFreshLookingToken() {
        String old = jwt(NOW.getEpochSecond() + 3600, CLIENT);
        String fresh = jwt(NOW.getEpochSecond() + 75_600, CLIENT);
        store.save(new GarminTokens(old, "refresh-1", CLIENT, NOW.plusSeconds(3600), NOW.plusSeconds(86_400)));
        garmin.respond(PROFILE_PATH, 401, "")
                .respond(TOKEN_PATH, 200, tokenReply(fresh, "refresh-2"))
                .respond(PROFILE_PATH, 200, PROFILE);

        GarminAuth.WhoAmI me = auth.whoami();

        assertThat(me.profile().friendlyName()).isEqualTo("Jo Runner");
        assertThat(garmin.requests(PROFILE_PATH)).extracting(r -> r.header("Authorization"))
                .containsExactly("Bearer " + old, "Bearer " + fresh);
        assertThat(store.load()).hasValueSatisfying(t -> assertThat(t.refreshToken()).isEqualTo("refresh-2"));
    }

    @Test
    void whoamiGivesUpAfterOneRefresh() {
        store.save(new GarminTokens(jwt(NOW.getEpochSecond() + 3600, CLIENT), "refresh-1", CLIENT,
                NOW.plusSeconds(3600), NOW.plusSeconds(86_400)));
        garmin.respond(PROFILE_PATH, 401, "")
                .respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-2"))
                .respond(PROFILE_PATH, 401, "");

        assertThatThrownBy(() -> auth.whoami()).isInstanceOf(SessionExpiredException.class);
        assertThat(garmin.requests(TOKEN_PATH)).hasSize(1);
        assertThat(garmin.requests(PROFILE_PATH)).hasSize(2);
    }

    private GarminAuth.WhoAmI login(String pasted) {
        return auth.login(new BufferedReader(new StringReader(pasted + "\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8));
    }
}
