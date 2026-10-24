package com.hammez.stride.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.GarminSamples;
import com.hammez.stride.garmin.auth.BrowserLogin;
import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminAuth;
import com.hammez.stride.garmin.auth.GarminAuthConfig;
import com.hammez.stride.garmin.auth.GarminTokens;
import com.hammez.stride.garmin.auth.ProfileClient;
import com.hammez.stride.garmin.auth.TokenManager;
import com.hammez.stride.garmin.auth.TokenStore;

import tools.jackson.databind.json.JsonMapper;

/** Command handling. Only the capture output test talks to a (fake) Garmin. */
class StrideCommandsTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final JsonMapper json = JsonMapper.builder().build();
    private TokenStore store;
    private GarminCache cache;
    private StrideCommands commands;

    @BeforeEach
    void setUp() {
        store = new TokenStore(temp.resolve("garmin_tokens.json"), JsonMapper.builder().build());
        // Nothing here should talk to Garmin, so the HTTP pieces are left out.
        GarminAuthConfig config = GarminAuthConfig.defaults();
        var api = new GarminApi(null, config, new TokenManager(store, null, config, Clock.systemUTC()));
        var auth = new GarminAuth(new BrowserLogin(config, url -> true), null,
                new ProfileClient(api, config, JsonMapper.builder().build()), store);
        var samples = new GarminSamples(api, GarminDataConfig.defaults(config), json, Clock.systemUTC(), ZoneOffset.UTC);
        cache = new GarminCache(temp.resolve("cache"), json, Clock.systemUTC());
        commands = new StrideCommands(auth, samples, cache, io(), ZoneOffset.UTC);
    }

    private CliIo io() {
        return new CliIo(new BufferedReader(new StringReader("not an address\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void noCommandPrintsUsage() {
        assertThat(commands.run(List.of())).isEqualTo(2);
        assertThat(err()).contains("Usage:");
    }

    @Test
    void unknownCommandPrintsUsage() {
        assertThat(commands.run(List.of("fetch"))).isEqualTo(2);
        assertThat(err()).contains("Unknown command: fetch", "Usage:");
    }

    @Test
    void helpPrintsUsage() {
        assertThat(commands.run(List.of("help"))).isZero();
        assertThat(out()).contains("login", "whoami", "logout", "capture <folder>");
    }

    @Test
    void captureNeedsExactlyOneFolder() {
        assertThat(commands.run(List.of("capture"))).isEqualTo(2);
        assertThat(commands.run(List.of("capture", "a", "b"))).isEqualTo(2);
        assertThat(err()).contains("Usage:");
    }

    @Test
    void captureRefusesAnOptionOrABadPathAsTheFolder() {
        // Spring takes --help as an option, so only single-dash ones arrive as the folder.
        assertThat(commands.run(List.of("capture", "-h"))).isEqualTo(2);
        assertThat(commands.run(List.of("capture", " "))).isEqualTo(2);
        assertThat(commands.run(List.of("capture", "bad\0name"))).isEqualTo(2);

        assertThat(err()).contains("capture needs a folder", "Not a folder path", "Usage:")
                .doesNotContain("Not logged in");
        assertThat(Path.of("-h")).doesNotExist();
    }

    @Test
    void captureWhenNotLoggedIn() {
        assertThat(commands.run(List.of("capture", temp.resolve("samples").toString()))).isEqualTo(1);
        assertThat(err()).contains("Not logged in. Run `stride-mcp login`.", "Nothing was saved.");
        assertThat(temp.resolve("samples")).doesNotExist();
    }

    @Test
    void capturePrintsWhereTheFilesWentAndTheReminder() {
        Path folder = temp.resolve("samples");
        try (var garmin = new FakeGarmin()) {
            String daily = "/usersummary-service/usersummary/daily/jo";
            String sleep = "/wellness-service/wellness/dailySleepData/jo";
            garmin.respond(FakeGarmin.PROFILE_PATH, 200, "{\"displayName\":\"jo\"}")
                    .respond("/userprofile-service/userprofile/personal-information/jo", 200, "{}")
                    .respond("/activitylist-service/activities/search/activities", 200, "[]")
                    .respond("/activitylist-service/activities/search/activities", 200, "[]")
                    .respond("/activity-service/activity/1", 404, "")
                    .respond(daily, 200, "{}").respond(daily, 200, "{}")
                    .respond(sleep, 200, "{}").respond(sleep, Map.of("date", "2000-01-01", "nonSleepBufferMinutes", "60"),
                            204, "");
            Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
            var samples = new GarminSamples(garmin.signedInApi(temp.resolve("garmin_tokens.json"), clock),
                    GarminDataConfig.defaults(garmin.config()), json, clock, ZoneOffset.UTC);
            var capture = new StrideCommands(null, samples, null, io(), ZoneOffset.UTC);

            assertThat(capture.run(List.of("capture", folder.toString()))).isZero();
        }

        assertThat(out()).contains(
                "Saved 9 files to " + folder + ":",
                "  activity-not-found.json      HTTP 404",
                "  sleep-none.json              HTTP 204",
                "No activities in the last 7 days",
                "These files hold personal data",
                "docs/phases/phase-2-data.md (\"Getting real samples\")")
                .doesNotContain(FakeGarmin.ACCESS_TOKEN).doesNotContain(FakeGarmin.REFRESH_TOKEN);
        assertThat(err()).isEmpty();
    }

    @Test
    void errorsGoToStderrWithExitCodeOne() {
        assertThat(commands.run(List.of("login"))).isEqualTo(1);
        assertThat(err()).contains("No ticket found");
    }

    @Test
    void logout() {
        store.save(new GarminTokens("access-secret", "refresh-secret", "C", Instant.EPOCH, null));

        assertThat(commands.run(List.of("logout"))).isZero();
        assertThat(commands.run(List.of("logout"))).isZero();

        assertThat(out()).contains("Logged out.", "Already logged out.").doesNotContain("secret", "cache");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void logoutAlsoDeletesTheCache() throws Exception {
        store.save(new GarminTokens("access-secret", "refresh-secret", "C", Instant.EPOCH, null));
        Path dir = ownCacheFolder();
        Files.writeString(dir.resolve("sleep-2026-10-22.json"), "{}");
        Files.writeString(dir.resolve(".summary-2026-10-22.json123.tmp"), "{}");

        assertThat(commands.run(List.of("logout"))).isZero();

        assertThat(out()).contains("Logged out.", "Deleted the cache in " + dir);
        assertThat(dir).doesNotExist();
        assertThat(store.load()).isEmpty();
    }

    @Test
    void logoutDeletesTheCacheEvenWhenAlreadyLoggedOut() throws Exception {
        Path dir = ownCacheFolder();
        Files.writeString(dir.resolve("social-profile.json"), "{}");

        assertThat(commands.run(List.of("logout"))).isZero();

        assertThat(out()).contains("Already logged out.", "Deleted the cache in " + dir);
        assertThat(dir).doesNotExist();
    }

    @Test
    void logoutDeletesTheCacheEvenIfTheTokenFileCantBeDeleted() throws Exception {
        Path dir = ownCacheFolder();
        Files.writeString(dir.resolve("sleep-2026-10-22.json"), "{}");
        // A non-empty folder where the token file should be can't be deleted.
        Files.createDirectories(store.file().resolve("blocker"));

        assertThat(commands.run(List.of("logout"))).isEqualTo(1);

        assertThat(err()).contains("Couldn't delete the token file");
        assertThat(dir).doesNotExist();
    }

    /** A cache folder as GarminCache makes it: with its marker file. */
    private Path ownCacheFolder() throws Exception {
        Path dir = Files.createDirectory(temp.resolve("cache"));
        Files.writeString(dir.resolve(".stride-mcp-cache"), "");
        return dir;
    }

    @Test
    void whoamiWhenNotLoggedIn() {
        assertThat(commands.run(List.of("whoami"))).isEqualTo(1);
        assertThat(err()).contains("Not logged in. Run `stride-mcp login`.");
    }

    @Test
    void unreadableTokenFileIsAClearErrorAndLogoutStillWorks() throws Exception {
        Files.writeString(store.file(), "{\"accessToken\":\"access-secret\"");

        assertThat(commands.run(List.of("whoami"))).isEqualTo(1);
        assertThat(err()).contains("unreadable").doesNotContain("secret");

        assertThat(commands.run(List.of("logout"))).isZero();
        assertThat(out()).contains("Logged out.");
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
