package com.hammez.stride.garmin.auth;

import static com.hammez.stride.garmin.auth.FakeGarmin.TOKEN_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.jwt;
import static com.hammez.stride.garmin.auth.FakeGarmin.tokenReply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class TokenManagerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String CLIENT = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private TokenStore store;
    private TokenManager manager;

    @BeforeEach
    void setUp() {
        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new TokenStore(temp.resolve("garmin_tokens.json"), json);
        manager = new TokenManager(store, new DiTokenClient(garmin.httpClient(), garmin.config(), json, clock),
                garmin.config(), clock);
    }

    @AfterEach
    void stop() {
        garmin.close();
    }

    @Test
    void notLoggedInWithoutATokenFile() {
        assertThatThrownBy(() -> manager.validTokens())
                .isInstanceOf(NotLoggedInException.class).hasMessage("Not logged in. Run `stride-mcp login`.");
    }

    @Test
    void usesSavedTokensThatAreStillFresh() {
        GarminTokens saved = tokens("refresh-1", NOW.plus(Duration.ofMinutes(16)), NOW.plus(Duration.ofDays(20)));
        store.save(saved);

        assertThat(manager.validTokens()).isEqualTo(saved);
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void refreshesAndSavesWithinFifteenMinutesOfExpiry() {
        store.save(tokens("refresh-1", NOW.plus(Duration.ofMinutes(14)), NOW.plus(Duration.ofDays(20))));
        String newAccess = jwt(NOW.getEpochSecond() + 75_600, CLIENT);
        garmin.respond(TOKEN_PATH, 200, tokenReply(newAccess, "refresh-2"));

        GarminTokens result = manager.validTokens();

        assertThat(result.accessToken()).isEqualTo(newAccess);
        assertThat(result.refreshToken()).isEqualTo("refresh-2");
        assertThat(store.load()).contains(result);
        assertThat(garmin.requests(TOKEN_PATH)).singleElement()
                .satisfies(r -> assertThat(r.form()).containsEntry("refresh_token", "refresh-1"));
    }

    @Test
    void refreshesAnAlreadyExpiredAccessToken() {
        store.save(tokens("refresh-1", NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(20))));
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-2"));

        assertThat(manager.validTokens().refreshToken()).isEqualTo("refresh-2");
    }

    @Test
    void expiredRefreshTokenMeansSessionExpiredWithoutAskingGarmin() {
        store.save(tokens("refresh-1", NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofMinutes(1))));

        assertThatThrownBy(() -> manager.validTokens()).isInstanceOf(SessionExpiredException.class);
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void rejectedRefreshTokenMeansSessionExpired() {
        GarminTokens saved = tokens("refresh-1", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(5)));
        store.save(saved);
        garmin.respond(TOKEN_PATH, 400, "{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> manager.validTokens())
                .isInstanceOf(SessionExpiredException.class).hasMessage("Session expired. Run `stride-mcp login`.");
        assertThat(store.load()).contains(saved);
    }

    @Test
    void refreshesATokenGarminRejectedEvenIfItLooksFresh() {
        GarminTokens saved = tokens("refresh-1", NOW.plus(Duration.ofHours(10)), NOW.plus(Duration.ofDays(20)));
        store.save(saved);
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-2"));

        GarminTokens result = manager.refreshRejected(saved);

        assertThat(result.refreshToken()).isEqualTo("refresh-2");
        assertThat(store.load()).contains(result);
    }

    @Test
    void usesTokensAnotherProcessAlreadyRefreshed() {
        GarminTokens rejected = tokens("refresh-1", NOW.plus(Duration.ofHours(10)), NOW.plus(Duration.ofDays(20)));
        GarminTokens newer = tokens("refresh-2", NOW.plus(Duration.ofHours(21)), NOW.plus(Duration.ofDays(30)));
        store.save(newer);

        assertThat(manager.refreshRejected(rejected)).isEqualTo(newer);
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void refreshesWhileHoldingTheTokenFileLock() {
        store.save(tokens("refresh-1", NOW.plus(Duration.ofMinutes(1)), NOW.plus(Duration.ofDays(20))));
        Path lockFile = temp.resolve("garmin_tokens.json.lock");
        garmin.respond(TOKEN_PATH, 200, tokenReply(jwt(NOW.getEpochSecond() + 75_600, CLIENT), "refresh-2"));
        TokenStore spy = new TokenStore(store.file(), JsonMapper.builder().build()) {
            @Override
            public void save(GarminTokens tokens) {
                assertThat(lockHeld(lockFile)).isTrue();
                super.save(tokens);
            }
        };
        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        new TokenManager(spy, new DiTokenClient(garmin.httpClient(), garmin.config(), json, clock), garmin.config(), clock)
                .validTokens();

        assertThat(lockHeld(lockFile)).isFalse();
        assertThat(store.load()).hasValueSatisfying(t -> assertThat(t.refreshToken()).isEqualTo("refresh-2"));
    }

    /** True if some channel already holds the lock. In one JVM, a second lock attempt says so with an exception. */
    private static boolean lockHeld(Path lockFile) {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            return lock == null;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GarminTokens tokens(String refreshToken, Instant accessExpiry, Instant refreshExpiry) {
        return new GarminTokens(jwt(accessExpiry.getEpochSecond(), CLIENT), refreshToken, CLIENT, accessExpiry, refreshExpiry);
    }
}
