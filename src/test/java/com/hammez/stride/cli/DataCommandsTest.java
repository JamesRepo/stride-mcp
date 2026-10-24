package com.hammez.stride.cli;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.Fixtures;
import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminAuthConfig;
import com.hammez.stride.garmin.auth.TokenManager;
import com.hammez.stride.garmin.auth.TokenStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * The data commands, on a real GarminClient and cache talking to a fake Garmin, with replies from the fixtures.
 * The profile's time zone is Europe/London, where it is already Sat 24 October (00:30), while in UTC (the
 * computer's zone here) it is still the 23rd. So "today" is the 24th.
 */
class DataCommandsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T23:30:00Z");

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
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private GarminApi api;

    @AfterEach
    void noTokenEverShows() {
        garmin.close();
        assertThat(out() + err()).doesNotContain(FakeGarmin.ACCESS_TOKEN).doesNotContain(FakeGarmin.REFRESH_TOKEN);
    }

    // activities

    @Test
    void activitiesFromTheRealList() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, Fixtures.read("activities.json"));

        assertThat(run("activities", "--days", "8")).isZero();

        assertThat(out()).isEqualTo("""
                Date        Type               Name               Distance  Time     Pace      Avg HR  Id
                Thu 22 Oct  stair_climbing     Stair Stepper             –  10:02    –         142     24816092240
                Thu 22 Oct  indoor_cycling     Indoor Cycling            –  8:23     –         120     24815932306
                Thu 22 Oct  running            Islington Running   5.05 km  36:01    7:08 /km  143     24810777073
                Wed 21 Oct  strength_training  Strength                  –  1:10:03  –         96      24801905256
                Tue 20 Oct  strength_training  Strength                  –  55:59    –         79      24793573630
                Mon 19 Oct  strength_training  Strength                  –  1:11:52  –         93      24778243382
                Sun 18 Oct  running            Islington Running   8.01 km  53:51    6:43 /km  149     24765567036
                Sat 17 Oct  strength_training  Strength                  –  1:15:29  –         112     24755552155
                8 activities in the last 8 days (Sat 17 Oct to Sat 24 Oct).
                """);
        assertThat(err()).isEmpty();
    }

    @Test
    void activitiesDefaultToTheLastSevenDaysInTheProfilesTimeZone() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        assertThat(run("activities")).isZero();

        // In UTC it would be the 17th to the 23rd.
        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams())
                .isEqualTo(Map.of("startDate", "2026-10-18", "endDate", "2026-10-24", "start", "0", "limit", "20"));
        assertThat(out()).isEqualTo("No activities in the last 7 days (Sun 18 Oct to Sat 24 Oct).\n");
    }

    @Test
    void activitiesSendTheTypeFilterWithEitherOptionForm() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]").respond(ACTIVITIES, 200, "[]");

        assertThat(run("activities", "--type", "running", "--days=3")).isZero();
        assertThat(run("activities", "--type=trail_running", "--days", "1")).isZero();

        assertThat(garmin.requests(ACTIVITIES)).extracting(FakeGarmin.Request::queryParams)
                .containsExactly(
                        Map.of("startDate", "2026-10-22", "endDate", "2026-10-24", "start", "0", "limit", "20",
                                "activityType", "running"),
                        Map.of("startDate", "2026-10-24", "endDate", "2026-10-24", "start", "0", "limit", "20",
                                "activityType", "trail_running"));
        assertThat(out()).isEqualTo("""
                No running activities in the last 3 days (Thu 22 Oct to Sat 24 Oct).
                No trail_running activities today (Sat 24 Oct).
                """);
    }

    @Test
    void anActivityWithMissingValuesShowsADashNeverNull() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[{\"activityId\":7,\"activityType\":{\"typeKey\":\"running\"}}]");

        assertThat(run("activities", "--type", "running")).isZero();

        assertThat(out()).isEqualTo("""
                Date  Type     Name  Distance  Time  Pace  Avg HR  Id
                –     running  –            –  –     –     –       7
                1 running activity in the last 7 days (Sun 18 Oct to Sat 24 Oct).
                """);
    }

    // activity

    @Test
    void activityFromTheRealReply() {
        garmin.respond(ACTIVITY, 200, Fixtures.read("activity.json"))
                .respond(SPLITS, 200, Fixtures.read("activity-splits.json"));

        assertThat(run("activity", "24816092240")).isZero();

        assertThat(out()).isEqualTo("""
                Stair Stepper
                Start            Thu 22 Oct 2026, 17:04
                Type             stair_climbing
                Id               24816092240
                Time             10:02
                Heart rate       avg 142, max 158 bpm
                Calories         133 kcal
                Training effect  2.0 aerobic, 0.0 anaerobic
                Training load    22

                Lap  Distance  Time   Pace  Avg HR  Max HR  Elevation
                1           –  10:02  –     142     158     –
                """);
        assertThat(err()).isEmpty();
    }

    @Test
    void aRunShowsPaceCadencePowerAndEachLap() {
        // Made up: the sample was a stair stepper session. Field names as in GarminClientTest.
        garmin.respond(ACTIVITY, 200, """
                {"activityId":24816092240,"activityName":"Hill reps","activityTypeDTO":{"typeKey":"running"},
                 "summaryDTO":{"startTimeLocal":"2026-10-22T07:06:50.0","distance":5052.19,"duration":2161.05,
                 "movingDuration":2151.48,"elevationGain":17.8,"averageSpeed":2.338,"maxSpeed":3.7,"averageHR":143.0,
                 "maxHR":169.0,"calories":1448.0,"averageRunCadence":162.5,"averagePower":301.6,"trainingEffect":3.2,
                 "anaerobicTrainingEffect":0.5,"activityTrainingLoad":85.3}}""")
                .respond(SPLITS, 200, """
                        {"lapDTOs":[
                         {"lapIndex":1,"distance":1000.0,"duration":418.2,"averageSpeed":2.391,"averageHR":139.0,
                          "maxHR":151.0,"elevationGain":4.0},
                         {"lapIndex":2,"distance":4052.19,"duration":3742.85,"averageSpeed":1.083}]}""");

        assertThat(run("activity", "24816092240")).isZero();

        assertThat(out()).isEqualTo("""
                Hill reps
                Start            Thu 22 Oct 2026, 07:06
                Type             running
                Id               24816092240
                Distance         5.05 km
                Time             36:01 (moving 35:51)
                Pace             7:08 /km, fastest 4:30 /km
                Elevation gain   18 m
                Heart rate       avg 143, max 169 bpm
                Cadence          163 steps/min
                Power            302 W
                Calories         1,448 kcal
                Training effect  3.2 aerobic, 0.5 anaerobic
                Training load    85

                Lap  Distance  Time     Pace       Avg HR  Max HR  Elevation
                1     1.00 km  6:58     6:58 /km   139     151     4 m
                2     4.05 km  1:02:23  15:23 /km  –       –       –
                """);
    }

    @Test
    void anActivityWithNoLaps() {
        garmin.respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{\"lapDTOs\":[]}");

        assertThat(run("activity", "24816092240")).isZero();

        assertThat(out()).endsWith("Training load    22\n\nNo laps.\n");
    }

    @Test
    void anActivityThatDoesntExistIsExitCodeOne() {
        garmin.respond("/activity-service/activity/1", 404, "");

        assertThat(run("activity", "1")).isEqualTo(1);

        assertThat(err()).isEqualTo("No activity with id 1. Check the id in `stride-mcp activities`.\n");
        assertThat(out()).isEmpty();
    }

    // summary

    @Test
    void summaryFromTheRealReply() {
        signedIn();
        garmin.respond(SUMMARY, Map.of("calendarDate", "2026-10-23"), 200, Fixtures.read("summary-today.json"));

        assertThat(run("summary", "2026-10-23")).isZero();

        assertThat(out()).isEqualTo("""
                Fri 23 Oct 2026
                Steps            3,532 (goal 11,190)
                Distance         3.18 km
                Calories         1,417 kcal (222 active)
                Heart rate       resting 45, min 43, max 130 bpm
                Stress           average 20 (0–100)
                Body Battery     lowest 30, highest 96
                Intensity        0 min moderate, 2 min vigorous
                Floors climbed   7
                """);
    }

    @Test
    void summaryDefaultsToTodayInTheProfilesTimeZone() {
        signedIn();
        garmin.respond(SUMMARY, Map.of("calendarDate", "2026-10-24"), 200, "{\"totalSteps\":null}");

        assertThat(run("summary")).isZero();

        assertThat(out()).isEqualTo("No data recorded for Sat 24 Oct 2026.\n");
    }

    // sleep

    @Test
    void sleepFromTheRealReply() {
        signedIn();
        garmin.respond(SLEEP, Map.of("date", "2026-10-23", "nonSleepBufferMinutes", "60"), 200,
                Fixtures.read("sleep-last-night.json"));

        assertThat(run("sleep", "2026-10-23")).isZero();

        // Times in the profile's time zone (British Summer Time), not the computer's (UTC here).
        assertThat(out()).isEqualTo("""
                Night ending Fri 23 Oct 2026
                Start            Thu 22 Oct, 22:37
                End              Fri 23 Oct, 07:37
                Asleep           8:52:00
                  Deep           1:31:00
                  Light          4:55:00
                  REM            2:26:00
                Awake            0:08:00
                Score            91 (Excellent)
                Respiration      12 breaths/min
                SpO2             97% average
                """);
    }

    @Test
    void sleepDefaultsToLastNightAndNoSleepIsNotAnError() {
        signedIn();
        garmin.respond(SLEEP, Map.of("date", "2026-10-24", "nonSleepBufferMinutes", "60"), 200,
                Fixtures.read("sleep-none.json"));

        assertThat(run("sleep")).isZero();

        assertThat(out()).isEqualTo("No sleep recorded for the night ending Sat 24 Oct.\n");
        assertThat(err()).isEmpty();
    }

    // The cache

    @Test
    void aSecondRunIsAnsweredFromTheCache() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, Fixtures.read("activities.json"))
                .respond(ACTIVITY, 200, Fixtures.read("activity.json"))
                .respond(SPLITS, 200, Fixtures.read("activity-splits.json"))
                .respond(SUMMARY, 200, Fixtures.read("summary-past.json"))
                .respond(SLEEP, 200, Fixtures.read("sleep-last-night.json"));
        List<List<String>> commands = List.of(List.of("activities"), List.of("activity", "24816092240"),
                List.of("summary", "2026-09-23"), List.of("sleep", "2026-10-23"));

        for (List<String> command : commands) {
            assertThat(run(command)).isZero();
            String first = out();
            out.reset();

            // A new process: new client and cache, on the same folder.
            assertThat(run(command)).as("again: " + command).isZero();
            assertThat(out()).isEqualTo(first);
            out.reset();
        }

        for (String path : List.of(PROFILE_PATH, PERSONAL, ACTIVITIES, ACTIVITY, SPLITS, SUMMARY, SLEEP)) {
            assertThat(garmin.requests(path)).as(path).hasSize(1);
        }
    }

    @Test
    void noCacheAsksGarminEvenWithAFreshCopyAndStillCachesTheReply() {
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"totalSteps\":100}").respond(SUMMARY, 200, "{\"totalSteps\":200}");

        assertThat(run("summary", "2026-10-22")).isZero();
        assertThat(run("summary", "--no-cache", "2026-10-22")).isZero();
        assertThat(run("summary", "2026-10-22")).isZero();

        assertThat(out()).containsSubsequence("Steps            100", "Steps            200", "Steps            200");
        assertThat(garmin.requests(SUMMARY)).hasSize(2);
        // The profile still comes from the cache.
        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
    }

    @Test
    void noCacheWorksWithEachDataCommand() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]").respond(ACTIVITIES, 200, "[]")
                .respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{}")
                .respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{}")
                .respond(SLEEP, 204, "").respond(SLEEP, 204, "");

        for (List<String> command : List.of(List.of("activities"), List.of("activity", "24816092240"),
                List.of("sleep"))) {
            assertThat(run(command)).isZero();
            assertThat(run(withNoCache(command))).isZero();
        }

        assertThat(garmin.requests(ACTIVITIES)).hasSize(2);
        assertThat(garmin.requests(ACTIVITY)).hasSize(2);
        assertThat(garmin.requests(SLEEP)).hasSize(2);
    }

    @Test
    void aCacheThatCantBeUsedIsOneLineOnStderrAndTheCommandStillWorks() throws Exception {
        Path cacheDir = Files.createSymbolicLink(temp.resolve("cache"), Files.createDirectory(temp.resolve("other")));
        signedIn();
        garmin.respond(SLEEP, 200, Fixtures.read("sleep-last-night.json"));

        assertThat(run("sleep", "2026-10-23")).isZero();

        assertThat(out()).startsWith("Night ending Fri 23 Oct 2026\n");
        assertThat(err()).isEqualTo("Not using the cache: " + cacheDir + " is a link or not a folder. "
                + "Remove it, or pick another folder with --stride.cache-dir.\n");
    }

    @Test
    void theCacheProblemIsShownAfterAGarminErrorToo() throws Exception {
        Files.createSymbolicLink(temp.resolve("cache"), Files.createDirectory(temp.resolve("other")));
        garmin.respond(PROFILE_PATH, 429, "");

        assertThat(run("sleep")).isEqualTo(1);

        List<String> lines = err().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("Garmin is rate limiting.");
        assertThat(lines.get(1)).startsWith("Not using the cache: ");
    }

    // Garmin errors are exit code 1

    @Test
    void rateLimitingIsExitCodeOne() {
        signedIn();
        garmin.respond(ACTIVITIES, 429, "");

        assertThat(run("activities")).isEqualTo(1);

        assertThat(err()).isEqualTo("Garmin is rate limiting. Wait a while (an hour or more) before trying again.\n");
        assertThat(out()).isEmpty();
    }

    @Test
    void notLoggedInIsExitCodeOne() {
        GarminAuthConfig config = garmin.config();
        api = new GarminApi(null, config,
                new TokenManager(new TokenStore(temp.resolve("none.json"), json), null, config, clock));

        assertThat(run("summary")).isEqualTo(1);

        assertThat(err()).isEqualTo("Not logged in. Run `stride-mcp login`.\n");
    }

    // Usage errors are exit code 2, and nothing is sent to Garmin

    @Test
    void badActivityIds() {
        for (String id : List.of("abc", "-5", "0", "1e5", "99999999999999999999", "")) {
            assertThat(run("activity", id)).as(id).isEqualTo(2);
        }

        assertThat(err().lines()).hasSize(6).containsOnly("Activity ids are numbers, like 20123456789.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void badDates() {
        for (String date : List.of("2026-13-01", "2026-02-30", "23-10-2026", "yesterday", "2026-10-2")) {
            assertThat(run("summary", date)).as(date).isEqualTo(2);
            assertThat(run("sleep", date)).as(date).isEqualTo(2);
        }

        assertThat(err().lines()).hasSize(10).containsOnly("Dates look like 2026-10-22.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void badDays() {
        for (List<String> args : List.of(List.of("--days", "0"), List.of("--days", "91"), List.of("--days=abc"),
                List.of("--days="), List.of("--days"), List.of("--days", "--no-cache"))) {
            assertThat(run(prepend("activities", args))).as(args.toString()).isEqualTo(2);
        }

        assertThat(err().lines()).hasSize(6).containsOnly("--days must be between 1 and 90.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void ninetyDaysIsAllowed() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        assertThat(run("activities", "--days", "90")).isZero();

        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams()).containsEntry("startDate", "2026-07-27");
    }

    @Test
    void aTypeWithNoValue() {
        assertThat(run("activities", "--type")).isEqualTo(2);
        assertThat(run("activities", "--type=")).isEqualTo(2);

        assertThat(err().lines()).containsOnly("--type needs an activity type, like --type running.");
    }

    @Test
    void aMissingOrExtraArgumentPrintsUsage() {
        for (List<String> args : List.of(List.of("activity"), List.of("activity", "1", "2"),
                List.of("activities", "7"), List.of("summary", "2026-10-22", "2026-10-23"),
                List.of("sleep", "2026-10-22", "extra"))) {
            out.reset();
            err.reset();
            assertThat(run(args)).as(args.toString()).isEqualTo(2);
            assertThat(err()).as(args.toString()).startsWith("Usage:");
        }
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void unknownOrMisplacedOptionsPrintUsage() {
        assertThat(run("activities", "--day", "7")).isEqualTo(2);
        assertThat(err()).startsWith("Unknown option: --day\nUsage:");
        err.reset();

        assertThat(run("activities", "--days", "7", "--days", "8")).isEqualTo(2);
        assertThat(err()).startsWith("--days is given twice.\nUsage:");
        err.reset();

        assertThat(run("summary", "--days", "3")).isEqualTo(2);
        assertThat(err()).startsWith("--days and --type only work with activities.\nUsage:");
        err.reset();

        assertThat(run("whoami", "--no-cache")).isEqualTo(2);
        assertThat(err()).startsWith("--no-cache only works with activities, activity, summary and sleep.\nUsage:");
        err.reset();

        assertThat(run("sleep", "--no-cache=yes")).isEqualTo(2);
        assertThat(err()).startsWith("--no-cache doesn't take a value.\nUsage:");

        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void springSettingsAreLeftToSpring() {
        signedIn();
        garmin.respond(SLEEP, 204, "");

        assertThat(run("--stride.cache-dir=/elsewhere", "sleep", "--logging.level.root=OFF")).isZero();

        assertThat(out()).isEqualTo("No sleep recorded for the night ending Sat 24 Oct.\n");
    }

    @Test
    void helpListsTheDataCommands() {
        assertThat(run("--help")).isZero();
        assertThat(run("activities", "--help")).isZero();

        assertThat(out()).contains("activities", "--days N", "--type T", "activity <id>", "summary [date]",
                "sleep [date]", "--no-cache", "Dates look like 2026-10-22.");
        assertThat(err()).isEmpty();
        assertThat(garmin.requests()).isEmpty();
    }

    private int run(String... args) {
        return run(List.of(args));
    }

    /** Runs a command as a new stride-mcp process would: a new client and cache, on the same folders. */
    private int run(List<String> args) {
        if (api == null) {
            api = garmin.signedInApi(temp.resolve("garmin_tokens.json"), clock);
        }
        var cache = new GarminCache(temp.resolve("cache"), json, clock);
        var client = new GarminClient(api, cache, GarminDataConfig.defaults(garmin.config()), json, clock,
                ZoneOffset.UTC);
        var io = new CliIo(new BufferedReader(new StringReader("")), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new StrideCommands(null, null, cache, client, io, ZoneOffset.UTC).run(args);
    }

    /** Queues the profile and its time zone (Europe/London) once. The cache keeps them after that. */
    private void signedIn() {
        garmin.respond(PROFILE_PATH, 200, Fixtures.read("social-profile.json"))
                .respond(PERSONAL, 200, Fixtures.read("personal-information.json"));
    }

    private static List<String> withNoCache(List<String> command) {
        return prepend("--no-cache", command);
    }

    private static List<String> prepend(String first, List<String> rest) {
        var all = new ArrayList<String>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
