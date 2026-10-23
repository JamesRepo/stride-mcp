package com.hammez.stride.garmin.auth;

import static com.hammez.stride.garmin.auth.FakeGarmin.TOKEN_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.jwt;
import static com.hammez.stride.garmin.auth.FakeGarmin.tokenReply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class DiTokenClientTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String TICKET = "ST-0000001-secretticket-cas";
    private static final String FIRST_ID = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2";
    private static final String SECOND_ID = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4";

    private final FakeGarmin garmin = new FakeGarmin();
    private final DiTokenClient client = new DiTokenClient(garmin.httpClient(), garmin.config(),
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void stop() {
        garmin.close();
    }

    @Test
    void swapsTicketForTokens() {
        long exp = NOW.plusSeconds(75_600).getEpochSecond();
        String access = jwt(exp, FIRST_ID);
        garmin.respond(TOKEN_PATH, 200, tokenReply(access, "refresh-1"));

        GarminTokens tokens = client.exchangeTicket(TICKET);

        assertThat(tokens.accessToken()).isEqualTo(access);
        assertThat(tokens.refreshToken()).isEqualTo("refresh-1");
        assertThat(tokens.clientId()).isEqualTo(FIRST_ID);
        assertThat(tokens.accessTokenExpiresAt()).isEqualTo(Instant.ofEpochSecond(exp));
        assertThat(tokens.refreshTokenExpiresAt()).isEqualTo(NOW.plusSeconds(2_592_000));

        FakeGarmin.Request request = garmin.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "client_id", FIRST_ID,
                "service_ticket", TICKET,
                "grant_type", "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket",
                "service_url", "https://mobile.integration.garmin.com/gcm/ios"));
        assertThat(request.header("Authorization")).isEqualTo("Basic " + basic(FIRST_ID));
        assertThat(request.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(request.header("User-Agent")).isEqualTo("GCM-Android-5.23");
        assertThat(request.header("X-Garmin-Client-Platform")).isEqualTo("Android");
    }

    @Test
    void usesTheClientIdInsideTheAccessToken() {
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 1000, "SOME_OTHER_ID"), "r"));

        assertThat(client.exchangeTicket(TICKET).clientId()).isEqualTo("SOME_OTHER_ID");
    }

    @Test
    void fallsBackToExpiresInWhenTheAccessTokenIsNotAJwt() {
        garmin.respond(TOKEN_PATH, 200, tokenReply("opaque-access", "r"));

        GarminTokens tokens = client.exchangeTicket(TICKET);

        assertThat(tokens.accessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(75_600));
        assertThat(tokens.clientId()).isEqualTo(FIRST_ID);
    }

    @Test
    void triesTheNextClientIdWhenOneIsRejected() {
        garmin.respond(TOKEN_PATH, 401, "{\"error\":\"invalid_client\"}")
                .respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 1000, SECOND_ID), "r"));

        GarminTokens tokens = client.exchangeTicket(TICKET);

        assertThat(tokens.clientId()).isEqualTo(SECOND_ID);
        assertThat(garmin.requests(TOKEN_PATH)).extracting(r -> r.form().get("client_id"))
                .containsExactly(FIRST_ID, SECOND_ID);
        assertThat(garmin.requests(TOKEN_PATH).get(1).header("Authorization")).isEqualTo("Basic " + basic(SECOND_ID));
    }

    @Test
    void stopsAtOnceWhenTheTicketIsRejected() {
        garmin.respond(TOKEN_PATH, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad ticket " + TICKET + "\"}");

        Throwable thrown = catchThrowable(() -> client.exchangeTicket(TICKET));

        assertThat(thrown).isInstanceOf(TicketRejectedException.class).hasMessageContaining("Run `stride-mcp login`");
        assertNoSecrets(thrown);
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void reportsWhenEveryClientIdIsRejected() {
        int count = garmin.config().clientIds().size();
        for (int i = 0; i < count; i++) {
            garmin.respond(TOKEN_PATH, 401, "{\"error\":\"unauthorized_client\"}");
        }

        Throwable thrown = catchThrowable(() -> client.exchangeTicket(TICKET));

        assertThat(thrown).isInstanceOf(ClientIdsRejectedException.class).hasMessageContaining("app IDs may have changed");
        assertThat(garmin.requests()).hasSize(count);
    }

    @Test
    void stopsOnRateLimit() {
        garmin.respond(TOKEN_PATH, 429, "Too Many Requests");

        assertThat(catchThrowable(() -> client.exchangeTicket(TICKET)))
                .isInstanceOf(RateLimitedException.class).hasMessageContaining("rate limiting");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void stopsOnForbidden() {
        garmin.respond(TOKEN_PATH, 403, "<html>blocked</html>");

        assertThat(catchThrowable(() -> client.exchangeTicket(TICKET)))
                .isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("HTTP 403");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void refreshStopsOnForbidden() {
        garmin.respond(TOKEN_PATH, 403, "");

        assertThat(catchThrowable(() -> client.refresh(saved("refresh-1"))))
                .isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("HTTP 403");
    }

    @Test
    void stopsOnServerErrorWithoutLeakingTheTicket() {
        garmin.respond(TOKEN_PATH, 503, "upstream failed for service_ticket=" + TICKET);

        Throwable thrown = catchThrowable(() -> client.exchangeTicket(TICKET));

        assertThat(thrown).isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("HTTP 503");
        assertNoSecrets(thrown);
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void reportsAReplyWithNoTokens() {
        garmin.respond(TOKEN_PATH, 200, "{\"token_type\":\"Bearer\"}");

        assertThat(catchThrowable(() -> client.exchangeTicket(TICKET)))
                .isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("missing a token");
    }

    @Test
    void refreshReturnsTheNewRefreshToken() {
        String newAccess = jwt(NOW.getEpochSecond() + 75_600, FIRST_ID);
        garmin.respond(TOKEN_PATH, 200, tokenReply(newAccess, "refresh-2"));
        GarminTokens old = saved("refresh-1");

        GarminTokens tokens = client.refresh(old);

        assertThat(tokens.accessToken()).isEqualTo(newAccess);
        assertThat(tokens.refreshToken()).isEqualTo("refresh-2");
        assertThat(tokens.refreshTokenExpiresAt()).isEqualTo(NOW.plusSeconds(2_592_000));
        FakeGarmin.Request request = garmin.requests().getFirst();
        assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "grant_type", "refresh_token", "client_id", FIRST_ID, "refresh_token", "refresh-1"));
        assertThat(request.header("Authorization")).isEqualTo("Basic " + basic(FIRST_ID));
    }

    @Test
    void rejectedRefreshTokenMeansSessionExpired() {
        for (int status : List.of(400, 401)) {
            garmin.respond(TOKEN_PATH, status, "{\"error\":\"invalid_grant\",\"refresh_token\":\"refresh-1\"}");

            Throwable thrown = catchThrowable(() -> client.refresh(saved("refresh-1")));

            assertThat(thrown).isInstanceOf(SessionExpiredException.class).hasMessageContaining("Session expired");
            assertNoSecrets(thrown);
        }
    }

    @Test
    void refreshStopsOnRateLimit() {
        garmin.respond(TOKEN_PATH, 429, "");

        assertThat(catchThrowable(() -> client.refresh(saved("refresh-1")))).isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void refreshServerErrorRedactsTokens() {
        garmin.respond(TOKEN_PATH, 500, "{\"refresh_token\":\"refresh-1\",\"detail\":\"boom\"}");

        Throwable thrown = catchThrowable(() -> client.refresh(saved("refresh-1")));

        assertThat(thrown).isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("HTTP 500");
        assertThat(thrown.getMessage()).doesNotContain("refresh-1");
    }

    @Test
    void anUnknownRejectionStopsWithoutTryingOtherClientIds() {
        for (String body : List.of("{\"error\":\"invalid_request\"}", "", "<html>Bad Request</html>")) {
            garmin.respond(TOKEN_PATH, 400, body);

            Throwable thrown = catchThrowable(() -> client.exchangeTicket(TICKET));

            assertThat(thrown).isExactlyInstanceOf(GarminAuthException.class)
                    .hasMessageContaining("HTTP 400").hasMessageContaining("Run `stride-mcp login` again");
            assertNoSecrets(thrown);
        }
        assertThat(garmin.requests()).hasSize(3);
    }

    @Test
    void refreshWithoutARefreshExpiryLeavesItUnknown() {
        garmin.respond(TOKEN_PATH, 200, """
                {"access_token":"%s","refresh_token":"refresh-2","expires_in":75600}"""
                .formatted(jwt(NOW.getEpochSecond() + 75_600, FIRST_ID)));

        assertThat(client.refresh(saved("refresh-1")).refreshTokenExpiresAt()).isNull();
    }

    @Test
    void refreshWithARejectedClientIdSaysSo() {
        garmin.respond(TOKEN_PATH, 400, "{\"error\":\"invalid_client\"}");

        assertThat(catchThrowable(() -> client.refresh(saved("refresh-1"))))
                .isInstanceOf(ClientIdsRejectedException.class).hasMessageContaining("app ID");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void refreshWithAnEmptyRejectionMeansSessionExpired() {
        garmin.respond(TOKEN_PATH, 401, "");

        assertThat(catchThrowable(() -> client.refresh(saved("refresh-1")))).isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void refreshWithAnUnknownRejectionShowsItWithoutTheRefreshToken() {
        garmin.respond(TOKEN_PATH, 400, "{\"error\":\"invalid_request\",\"error_description\":\"bad refresh-1\"}");

        Throwable thrown = catchThrowable(() -> client.refresh(saved("refresh-1")));

        assertThat(thrown).isExactlyInstanceOf(GarminAuthException.class).hasMessageContaining("invalid_request");
        assertNoSecrets(thrown);
    }

    private static GarminTokens saved(String refreshToken) {
        return new GarminTokens(jwt(NOW.getEpochSecond(), FIRST_ID), refreshToken, FIRST_ID, NOW, NOW.plusSeconds(86_400));
    }

    private static String basic(String clientId) {
        return Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
    }

    private static void assertNoSecrets(Throwable thrown) {
        assertThat(thrown.getMessage()).doesNotContain(TICKET).doesNotContain("secretticket").doesNotContain("refresh-1");
    }
}
