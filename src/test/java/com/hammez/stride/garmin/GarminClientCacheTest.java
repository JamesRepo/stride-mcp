package com.hammez.stride.garmin;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;
import com.hammez.stride.garmin.auth.RateLimitedException;

import tools.jackson.databind.json.JsonMapper;

/**
 * GarminClient with the cache: what it asks Garmin, and for how long a reply is kept.
 * The profile's time zone is Europe/London, where it is already Sat 24 October at the start (00:30), while in UTC
 * it is still the 23rd. So "today" is the 24th, "yesterday" the 23rd, and the 22nd is the first older day.
 */
class GarminClientCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-23T23:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate OLDER = TODAY.minusDays(2);

    // Decoded, as Garmin sees them. The fixture's display name is "jo runner".
    private static final String PERSONAL = "/userprofile-service/userprofile/personal-information/jo runner";
    private static final String ACTIVITIES = "/activitylist-service/activities/search/activities";
    private static final String ACTIVITY = "/activity-service/activity/24816092240";
    private static final String SPLITS = "/activity-service/activity/24816092240/splits";
    private static final String SUMMARY = "/usersummary-service/usersummary/daily/jo runner";
    private static final String SLEEP = "/wellness-service/wellness/dailySleepData/jo runner";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private final JsonMapper json = JsonMapper.builder().build();
    /** For the client and the cache. The tokens use a fixed clock, so they never need refreshing. */
    private final TestClock clock = new TestClock(NOW);
    private GarminApi api;

    @AfterEach
    void stop() {
        garmin.close();
    }

    // Hits and misses

    @Test
    void aFreshCopyMakesNoRequestEvenFromANewClient() {
        signedIn();
        garmin.respond(SLEEP, 200, fixture("sleep-last-night.json"));
        Sleep first = client().sleep(OLDER).orElseThrow();

        // A new process: a new client and cache, on the same folder.
        Sleep again = client().sleep(OLDER).orElseThrow();

        assertThat(again).isEqualTo(first);
        assertThat(garmin.requests(SLEEP)).hasSize(1);
    }

    @Test
    void theDisplayNameAndTimeZoneSurviveANewClient() {
        signedIn();
        assertThat(client().today()).isEqualTo(TODAY);

        assertThat(client().today()).isEqualTo(TODAY);
        garmin.respond(SUMMARY, 200, "{}");
        client().dailySummary(OLDER);

        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
        assertThat(garmin.requests(PERSONAL)).hasSize(1);
    }

    @Test
    void theCacheNamesFilesAfterWhatTheyHold() {
        signedIn();
        garmin.respond(SLEEP, 200, fixture("sleep-last-night.json"))
                .respond(SUMMARY, 200, "{}")
                .respond(ACTIVITY, 200, fixture("activity.json")).respond(SPLITS, 200, fixture("activity-splits.json"))
                .respond(ACTIVITIES, 200, "[]");
        GarminClient client = client();

        client.sleep(OLDER);
        client.dailySummary(OLDER);
        client.activity(24816092240L);
        client.activities(OLDER.minusDays(6), OLDER, "trail_running");

        assertThat(cacheFiles()).containsExactlyInAnyOrder("social-profile.json", "personal-information.json",
                "sleep-2026-10-22.json", "summary-2026-10-22.json", "activity-24816092240.json",
                "activity-24816092240-splits.json", "activities-2026-10-16-to-2026-10-22-trail_running-page-1.json");
    }

    @Test
    void anActivityTypeThatIsntAPlainKeyIsMadeSafeForTheFileName() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        client().activities(OLDER, OLDER, "../Trail run");
        client().activities(OLDER, OLDER, "../Trail run");

        assertThat(cacheFiles()).contains("activities-2026-10-22-to-2026-10-22-~2e~2e~2f~54rail~20run-page-1.json");
        assertThat(garmin.requests(ACTIVITIES)).hasSize(1);
    }

    @Test
    void skippingTheCacheAsksGarminAndRewritesTheFile() {
        signedIn();
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"totalSteps\":100}").respond(SUMMARY, 200, "{\"totalSteps\":200}");
        GarminClient client = client();
        client.dailySummary(OLDER);

        assertThat(client.skippingCache().dailySummary(OLDER).steps()).isEqualTo(200);

        assertThat(garmin.requests(SUMMARY)).hasSize(2);
        // The new reply replaced the old one, and still lasts as long.
        clock.advance(Duration.ofDays(29));
        assertThat(client.dailySummary(OLDER).steps()).isEqualTo(200);
        assertThat(garmin.requests(SUMMARY)).hasSize(2);
    }

    @Test
    void skippingTheCacheStillTakesTheProfileFromIt() {
        signedIn();
        garmin.respond(SLEEP, 204, "").respond(SLEEP, 204, "");
        client().sleep(OLDER);

        client().skippingCache().sleep(OLDER);

        assertThat(garmin.requests(SLEEP)).hasSize(2);
        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
    }

    @Test
    void aBrokenFileIsIgnoredAndReplaced() throws IOException {
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"totalSteps\":100}").respond(SUMMARY, 200, "{\"totalSteps\":200}");
        client().dailySummary(OLDER);
        Files.writeString(temp.resolve("cache/summary-2026-10-22.json"), "{\"savedAt\":");

        assertThat(client().dailySummary(OLDER).steps()).isEqualTo(200);

        assertThat(client().dailySummary(OLDER).steps()).isEqualTo(200);
        assertThat(garmin.requests(SUMMARY)).hasSize(2);
    }

    @Test
    void aSavedReplyThatNoLongerReadsIsAskedForAgain() {
        signedIn();
        garmin.respond(SLEEP, 200, fixture("sleep-last-night.json"));
        // A well-formed cache file whose reply isn't a shape GarminReplies accepts.
        new GarminCache(temp.resolve("cache"), json, clock).put("sleep-2026-10-22", "[]", Duration.ofDays(30));

        assertThat(client().sleep(OLDER)).isPresent();

        assertThat(client().sleep(OLDER)).isPresent();
        assertThat(garmin.requests(SLEEP)).hasSize(1);
    }

    @Test
    void anActivityIsOnlyTakenFromTheCacheWithItsLaps() throws IOException {
        garmin.respond(ACTIVITY, 200, fixture("activity.json")).respond(SPLITS, 200, fixture("activity-splits.json"))
                .respond(ACTIVITY, 200, fixture("activity.json")).respond(SPLITS, 200, fixture("activity-splits.json"));
        client().activity(24816092240L);
        Files.delete(temp.resolve("cache/activity-24816092240-splits.json"));

        assertThat(client().activity(24816092240L).laps()).hasSize(1);

        assertThat(garmin.requests(ACTIVITY)).hasSize(2);
        assertThat(garmin.requests(SPLITS)).hasSize(2);
        assertThat(cacheFiles()).contains("activity-24816092240-splits.json");
    }

    @Test
    void aPagedSearchComesFromTheCacheOnlyIfEveryPageIsThere() throws IOException {
        signedIn();
        garmin.respond(ACTIVITIES, 200, page(1, 20)).respond(ACTIVITIES, 200, page(21, 3))
                .respond(ACTIVITIES, 200, page(1, 20)).respond(ACTIVITIES, 200, page(21, 4));
        assertThat(client().activities(OLDER, OLDER, null)).hasSize(23);

        assertThat(client().activities(OLDER, OLDER, null)).hasSize(23);
        assertThat(garmin.requests(ACTIVITIES)).hasSize(2);

        Files.delete(temp.resolve("cache/activities-2026-10-22-to-2026-10-22-page-2.json"));
        assertThat(client().activities(OLDER, OLDER, null)).hasSize(24);
        assertThat(garmin.requests(ACTIVITIES)).hasSize(4);
    }

    @Test
    void aBrokenPageIsDeletedEvenIfAskingGarminAgainFails() {
        signedIn();
        new GarminCache(temp.resolve("cache"), json, clock)
                .put("activities-2026-10-22-to-2026-10-22-page-1", "{}", Duration.ofDays(1));
        garmin.respond(ACTIVITIES, 429, "");

        assertThat(catchThrowable(() -> client().activities(OLDER, OLDER, null)))
                .isInstanceOf(RateLimitedException.class);

        assertThat(cacheFiles()).doesNotContain("activities-2026-10-22-to-2026-10-22-page-1.json");
    }

    @Test
    void aBrokenActivityIsDeletedEvenIfAskingGarminAgainFails() {
        GarminCache cache = new GarminCache(temp.resolve("cache"), json, clock);
        cache.put("activity-24816092240", "[]", Duration.ofDays(7));
        cache.put("activity-24816092240-splits", "{}", Duration.ofDays(7));
        garmin.respond(ACTIVITY, 429, "");

        assertThat(catchThrowable(() -> client().activity(24816092240L))).isInstanceOf(RateLimitedException.class);

        assertThat(cacheFiles()).isEmpty();
    }

    // The profile

    @Test
    void theProfileIsLookedUpOncePerClientEvenWithoutAUsableCache() throws IOException {
        Files.createSymbolicLink(temp.resolve("cache"), Files.createDirectory(temp.resolve("elsewhere")));
        signedIn();
        garmin.respond(SUMMARY, 200, "{}").respond(SUMMARY, 200, "{}").respond(SLEEP, 204, "");
        GarminClient client = client();

        client.dailySummary(OLDER);
        client.skippingCache().dailySummary(OLDER.minusDays(1));
        client.sleep(OLDER);
        client.today();

        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
        assertThat(garmin.requests(PERSONAL)).hasSize(1);
    }

    @Test
    void aTimeZoneThatIsntThereIsLookedUpOncePerClient() {
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json")).respond(PERSONAL, 404, "");
        GarminClient client = client();

        assertThat(client.today()).isEqualTo(YESTERDAY);
        assertThat(client.today()).isEqualTo(YESTERDAY);

        assertThat(garmin.requests(PERSONAL)).hasSize(1);
    }

    @Test
    void theRememberedProfileLastsSevenDays() {
        signedIn();
        signedIn();
        GarminClient client = client();
        expectLifetime(Duration.ofDays(7), PROFILE_PATH, client::today);
    }

    @Test
    void activitiesLookUpTheProfileBeforeAskingForAnyPage() {
        garmin.respond(PROFILE_PATH, 429, "");

        assertThat(catchThrowable(() -> client().activities(OLDER, OLDER, null)))
                .isInstanceOf(RateLimitedException.class);

        assertThat(garmin.requests(ACTIVITIES)).isEmpty();
    }

    // Errors are never cached

    @Test
    void errorsAreNotCached() {
        signedIn();
        garmin.respond(SUMMARY, 429, "").respond(SUMMARY, 200, "{\"totalSteps\":100}");

        assertThat(catchThrowable(() -> client().dailySummary(OLDER))).isInstanceOf(RateLimitedException.class);
        assertThat(client().dailySummary(OLDER).steps()).isEqualTo(100);

        assertThat(garmin.requests(SUMMARY)).hasSize(2);
        assertThat(cacheFiles()).contains("summary-2026-10-22.json");
    }

    @Test
    void aMissingActivityIsNotCached() {
        garmin.respond(ACTIVITY, 404, "").respond(ACTIVITY, 404, "");

        for (int i = 0; i < 2; i++) {
            assertThat(catchThrowable(() -> client().activity(24816092240L))).isInstanceOf(NotFoundException.class);
        }

        assertThat(garmin.requests(ACTIVITY)).hasSize(2);
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void repliesThatDontReadAreNotCached() {
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"privacyProtected\":true}").respond(SUMMARY, 200, "{\"privacyProtected\":true}")
                .respond(SLEEP, 200, "<html>Maintenance</html>").respond(SLEEP, 200, "<html>Maintenance</html>");

        for (int i = 0; i < 2; i++) {
            assertThat(catchThrowable(() -> client().dailySummary(OLDER))).isExactlyInstanceOf(GarminException.class);
            assertThat(catchThrowable(() -> client().sleep(OLDER))).isInstanceOf(UnexpectedReplyException.class);
        }

        assertThat(garmin.requests(SUMMARY)).hasSize(2);
        assertThat(garmin.requests(SLEEP)).hasSize(2);
    }

    @Test
    void anActivitySearchOverThePageLimitIsNotCached() {
        signedIn();
        for (int i = 0; i < 10; i++) {
            garmin.respond(ACTIVITIES, 200, page(i * 20 + 1, 20));
        }

        assertThat(catchThrowable(() -> client().activities(OLDER, OLDER, null))).isInstanceOf(GarminException.class);

        assertThat(cacheFiles()).doesNotContain("activities-2026-10-22-to-2026-10-22-page-1.json");
    }

    // Lifetimes, each side of the boundary

    @Test
    void theProfileLastsSevenDays() {
        signedIn();
        signedIn();
        expectLifetime(Duration.ofDays(7), PROFILE_PATH, () -> client().today());
        assertThat(garmin.requests(PERSONAL)).hasSize(2);
    }

    @Test
    void aSummaryForTodayOrYesterdayLastsFifteenMinutes() {
        signedIn();
        expectLifetime(Duration.ofMinutes(15), SUMMARY, "{\"totalSteps\":100}", () -> client().dailySummary(TODAY));
        expectLifetime(Duration.ofMinutes(15), SUMMARY, "{\"totalSteps\":100}", () -> client().dailySummary(YESTERDAY));
    }

    @Test
    void aSummaryForAnOlderDayLastsThirtyDays() {
        signedIn();
        signedIn();
        expectLifetime(Duration.ofDays(30), SUMMARY, "{\"totalSteps\":100}", () -> client().dailySummary(OLDER));
    }

    @Test
    void sleepForLastNightLastsFifteenMinutesAndForAnOlderNightThirtyDays() {
        signedIn();
        signedIn();
        expectLifetime(Duration.ofMinutes(15), SLEEP, fixture("sleep-last-night.json"), () -> client().sleep(TODAY));
        expectLifetime(Duration.ofDays(30), SLEEP, fixture("sleep-last-night.json"), () -> client().sleep(OLDER));
    }

    @Test
    void anEmptyReplyForAnOlderDayLastsAnHour() {
        signedIn();
        // Real: a night with no sleep is a 200 with a dailySleepDTO full of nulls.
        expectLifetime(Duration.ofHours(1), SLEEP, fixture("sleep-none.json"), () -> client().sleep(OLDER));
        expectLifetime(Duration.ofHours(1), SUMMARY, "{\"totalSteps\":null}", () -> client().dailySummary(OLDER));
        expectLifetime(Duration.ofHours(1), ACTIVITIES, "[]", () -> client().activities(OLDER, OLDER, null));
    }

    @Test
    void anEmptyReplyForTodayLastsFifteenMinutes() {
        signedIn();
        expectLifetime(Duration.ofMinutes(15), SLEEP, fixture("sleep-none.json"), () -> client().sleep(TODAY));
    }

    @Test
    void anActivityListIncludingTodayOrYesterdayLastsTenMinutes() {
        signedIn();
        expectLifetime(Duration.ofMinutes(10), ACTIVITIES, "[{\"activityId\":1}]",
                () -> client().activities(OLDER, YESTERDAY, null));
        expectLifetime(Duration.ofMinutes(10), ACTIVITIES, "[{\"activityId\":1}]",
                () -> client().activities(TODAY.minusDays(6), TODAY, null));
    }

    @Test
    void anActivityListEndingBeforeYesterdayLastsADay() {
        signedIn();
        expectLifetime(Duration.ofDays(1), ACTIVITIES, "[{\"activityId\":1}]",
                () -> client().activities(OLDER.minusDays(6), OLDER, null));
    }

    @Test
    void oneActivityAndItsLapsLastSevenDays() {
        garmin.respond(SPLITS, 200, fixture("activity-splits.json"))
                .respond(SPLITS, 200, fixture("activity-splits.json"));

        expectLifetime(Duration.ofDays(7), ACTIVITY, fixture("activity.json"), () -> client().activity(24816092240L));

        assertThat(garmin.requests(SPLITS)).hasSize(2);
    }

    @Test
    void aDayFetchedAsYesterdayStaysShortLivedOnceItIsOlder() {
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"totalSteps\":100}").respond(SUMMARY, 200, "{\"totalSteps\":200}");
        client().dailySummary(YESTERDAY);

        // A day later the 23rd is an older day, but the copy was saved while it was still changing.
        clock.advance(Duration.ofDays(1));
        signedIn();
        assertThat(client().dailySummary(YESTERDAY).steps()).isEqualTo(200);
    }

    /**
     * Asks once (from Garmin), then just before the lifetime ends (from the cache), then when it ends (from Garmin
     * again). Each request to {@code path} answers {@code reply}.
     */
    private void expectLifetime(Duration lifetime, String path, String reply, Runnable call) {
        garmin.respond(path, 200, reply).respond(path, 200, reply);
        expectLifetime(lifetime, path, call);
    }

    private void expectLifetime(Duration lifetime, String path, Runnable call) {
        int before = garmin.requests(path).size();
        call.run();
        assertThat(garmin.requests(path)).as("first ask").hasSize(before + 1);

        clock.advance(lifetime.minusSeconds(1));
        call.run();
        assertThat(garmin.requests(path)).as("a second before " + lifetime).hasSize(before + 1);

        clock.advance(Duration.ofSeconds(1));
        call.run();
        assertThat(garmin.requests(path)).as("at " + lifetime).hasSize(before + 2);
    }

    /** A new client and cache on the same folder, as a new stride-mcp process would make. */
    private GarminClient client() {
        if (api == null) {
            api = garmin.signedInApi(temp.resolve("garmin_tokens.json"), Clock.fixed(NOW, ZoneOffset.UTC));
        }
        return new GarminClient(api, new GarminCache(temp.resolve("cache"), json, clock),
                GarminDataConfig.defaults(garmin.config()), json, clock, ZoneOffset.UTC);
    }

    /** Queues the profile and its time zone (Europe/London) once. */
    private void signedIn() {
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json"))
                .respond(PERSONAL, 200, fixture("personal-information.json"));
    }

    /** The cache's files, without its marker. */
    private List<String> cacheFiles() {
        try (var files = Files.list(temp.resolve("cache"))) {
            return files.map(p -> p.getFileName().toString()).filter(name -> !name.equals(GarminCache.MARKER)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String page(int first, int count) {
        return IntStream.range(first, first + count).mapToObj(id -> "{\"activityId\":" + id + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String fixture(String name) {
        try (InputStream in = GarminClientCacheTest.class.getResourceAsStream("/garmin/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
