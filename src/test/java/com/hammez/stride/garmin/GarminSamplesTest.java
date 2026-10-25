package com.hammez.stride.garmin;

import static com.hammez.stride.garmin.auth.FakeGarmin.ACCESS_TOKEN;
import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.REFRESH_TOKEN;
import static com.hammez.stride.garmin.auth.FakeGarmin.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.GarminSamples.Capture;
import com.hammez.stride.garmin.GarminSamples.Saved;
import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.RateLimitedException;
import com.hammez.stride.garmin.auth.SessionExpiredException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The capture command's calls and files, against a fake Garmin. */
class GarminSamplesTest {

    /** 00:30 on Sat 24 October in London (still summer time), but 23:30 on the 23rd in UTC. */
    private static final Instant NOW = Instant.parse("2026-10-23T23:30:00Z");
    private static final LocalDate LONDON_TODAY = LocalDate.of(2026, 10, 24);
    private static final LocalDate UTC_TODAY = LocalDate.of(2026, 10, 23);

    // Decoded, as Garmin sees them. The space in the display name checks it's encoded into the path.
    private static final String PERSONAL = "/userprofile-service/userprofile/personal-information/jo runner";
    private static final String ACTIVITIES = "/activitylist-service/activities/search/activities";
    private static final String ACTIVITY = "/activity-service/activity/20123456789";
    private static final String SPLITS = "/activity-service/activity/20123456789/splits";
    private static final String MISSING = "/activity-service/activity/1";
    private static final String SUMMARY = "/usersummary-service/usersummary/daily/jo runner";
    private static final String SLEEP = "/wellness-service/wellness/dailySleepData/jo runner";

    private static final String PROFILE_BODY = "{\"displayName\":\"jo runner\",\"fullName\":\"Jo Runner\"}";
    private static final String LONDON = "{\"timeZone\":\"Europe/London\",\"userInfo\":{\"email\":\"jo@example.com\"}}";
    private static final String TWO_ACTIVITIES = """
            [{"activityId":20123456789,"activityName":"Morning Run"},\
            {"activityId":20098765432,"activityName":"Long hill run"}]""";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private final JsonMapper json = JsonMapper.builder().build();
    private Path folder;
    private GarminSamples samples;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        folder = temp.resolve("samples");
        samples = new GarminSamples(garmin.signedInApi(temp.resolve("tokens/garmin_tokens.json"), clock),
                GarminDataConfig.defaults(garmin.config()), json, clock, ZoneOffset.UTC);
    }

    @AfterEach
    void stop() {
        garmin.close();
    }

    @Test
    void savesEveryReplyWithItsStatusAndBody() throws IOException {
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);

        Capture capture = samples.capture(folder);

        assertThat(capture.folder()).isEqualTo(folder);
        assertThat(capture.saved()).containsExactly(
                new Saved("social-profile.json", 200),
                new Saved("personal-information.json", 200),
                new Saved("activities.json", 200),
                new Saved("activities-empty.json", 200),
                new Saved("activity.json", 200),
                new Saved("activity-splits.json", 200),
                new Saved("activity-not-found.json", 404),
                new Saved("summary-today.json", 200),
                new Saved("summary-past.json", 200),
                new Saved("sleep-last-night.json", 200),
                new Saved("sleep-none.json", 204),
                new Saved("weight-today.json", 200),
                new Saved("weight-none.json", 200));
        assertThat(capture.notes()).containsExactly("Today is 2026-10-24 in Europe/London (from your Garmin profile).");

        assertThat(read("social-profile.json").path("body").path("displayName").asString()).isEqualTo("jo runner");
        assertThat(read("activities.json").path("body")).hasSize(2);
        assertThat(read("activities-empty.json").path("body").isArray()).isTrue();
        assertThat(read("activities-empty.json").path("body")).isEmpty();
        assertThat(read("activity.json").path("body").path("activityId").asLong()).isEqualTo(20123456789L);
        assertThat(read("activity-splits.json").path("body").path("lapDTOs")).hasSize(1);
        assertThat(read("summary-today.json").path("body").path("totalSteps").asInt()).isEqualTo(9012);
        assertThat(read("summary-past.json").path("body").path("calendarDate").asString()).isEqualTo("2026-09-24");
        assertThat(read("sleep-last-night.json").path("body").path("dailySleepDTO").isObject()).isTrue();
        assertThat(read("sleep-none.json").path("status").asInt()).isEqualTo(204);
        assertThat(read("sleep-none.json").path("body").asString()).isEmpty();
        assertThat(read("weight-today.json").path("body").path("dateWeightList")).hasSize(1);
        assertThat(read("weight-none.json").path("body").path("dateWeightList")).isEmpty();

        // Each request made once, in order, with no retries and no refresh.
        assertThat(garmin.requests()).extracting(FakeGarmin.Request::path).containsExactly(
                PROFILE_PATH, PERSONAL, ACTIVITIES, ACTIVITIES, ACTIVITY, SPLITS, MISSING, SUMMARY, SUMMARY, SLEEP,
                SLEEP, weight("2026-10-24"), weight("2000-01-01"));
        assertThat(garmin.requests(ACTIVITIES)).extracting(FakeGarmin.Request::queryParams).containsExactly(
                activitiesQuery("2026-10-18", "2026-10-24"), activitiesQuery("2000-01-01", "2000-01-07"));
        assertThat(garmin.requests(SLEEP)).extracting(FakeGarmin.Request::queryParams).containsExactly(
                sleepQuery("2026-10-24"), sleepQuery("2000-01-01"));
        assertThat(garmin.requests()).filteredOn(r -> r.path().startsWith("/weight-service/"))
                .extracting(FakeGarmin.Request::queryParams).containsOnly(Map.of("includeAll", "true"));
    }

    @Test
    void theMissingActivityIsSavedAsA404AndTheRunCarriesOn() throws IOException {
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);

        samples.capture(folder);

        JsonNode notFound = read("activity-not-found.json");
        assertThat(notFound.path("status").asInt()).isEqualTo(404);
        assertThat(notFound.path("message").asString()).contains("HTTP 404", MISSING);
        assertThat(notFound.has("body")).isFalse();
        assertThat(Files.exists(folder.resolve("sleep-none.json"))).isTrue();
    }

    @Test
    void aReplyThatIsntJsonIsSavedAsText() throws IOException {
        garmin.respond(SUMMARY, Map.of("calendarDate", "2026-09-24"), 200, "<html>Not today</html>");
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);

        samples.capture(folder);

        assertThat(read("summary-past.json").path("body").asString()).isEqualTo("<html>Not today</html>");
    }

    @Test
    void noActivitiesSkipsTheActivityAndItsSplits() throws IOException {
        queueRun(LONDON_TODAY, LONDON, "[]");

        Capture capture = samples.capture(folder);

        assertThat(capture.saved()).extracting(Saved::file).doesNotContain("activity.json", "activity-splits.json")
                .hasSize(11);
        assertThat(capture.notes()).anySatisfy(note -> assertThat(note)
                .startsWith("No activities in the last 7 days, so activity.json and activity-splits.json were skipped."));
        assertThat(read("activities.json").path("body")).isEmpty();
        assertThat(garmin.requests(ACTIVITY)).isEmpty();
        assertThat(garmin.requests(SPLITS)).isEmpty();
        assertThat(garmin.requests(MISSING)).hasSize(1);
    }

    @Test
    void anActivityListWithoutIdsSkipsTheActivityAndSaysSo() {
        queueRun(LONDON_TODAY, LONDON, "[{\"activityName\":\"No id\"}]");

        Capture capture = samples.capture(folder);

        assertThat(capture.notes()).anySatisfy(note -> assertThat(note).contains("no activityId", "skipped"));
        assertThat(garmin.requests(ACTIVITY)).isEmpty();
    }

    @Test
    void anActivitySearchThatIsntAListSaysSo() {
        garmin.respond(ACTIVITIES, activitiesQuery("2026-10-18", "2026-10-24"), 404, "");
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);

        Capture capture = samples.capture(folder);

        assertThat(capture.notes()).contains("activities.json wasn't a list of activities (HTTP 404), "
                + "so activity.json and activity-splits.json were skipped.");
        assertThat(garmin.requests(ACTIVITY)).isEmpty();
    }

    @Test
    void usesTheComputersTimeZoneWhenTheProfileHasNone() {
        queueRun(UTC_TODAY, "{\"userInfo\":{\"timeZone\":\"Not/AZone\"}}", "[]");

        Capture capture = samples.capture(folder);

        assertThat(capture.notes()).contains(
                "Today is 2026-10-23 in Z (this computer's time zone: personal-information.json had none stride-mcp "
                        + "could read).");
        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams())
                .isEqualTo(activitiesQuery("2026-10-17", "2026-10-23"));
        assertThat(garmin.requests(SUMMARY)).extracting(r -> r.queryParams().get("calendarDate"))
                .containsExactly("2026-10-23", "2026-09-23");
    }

    @Test
    void readsTheTimeZoneFromUserInfoToo() {
        queueRun(LONDON_TODAY, "{\"userInfo\":{\"timeZone\":\"Europe/London\"}}", "[]");

        assertThat(samples.capture(folder).notes()).contains("Today is 2026-10-24 in Europe/London (from your Garmin profile).");
    }

    @Test
    void rateLimitingStopsTheRunAtOnceAndSaysWhatWasSaved() throws IOException {
        garmin.respond(PROFILE_PATH, 200, PROFILE_BODY)
                .respond(PERSONAL, 200, LONDON)
                .respond(ACTIVITIES, 429, "");

        Throwable thrown = catchThrowable(() -> samples.capture(folder));

        assertThat(thrown).isExactlyInstanceOf(GarminException.class).hasCauseInstanceOf(RateLimitedException.class)
                .hasMessageContaining("Garmin is rate limiting")
                .hasMessageContaining("Capture stopped at activities.json.")
                .hasMessageContaining("Saved before stopping, in " + folder + ": social-profile.json, "
                        + "personal-information.json.");
        assertThat(garmin.requests()).hasSize(3);
        assertThat(files()).containsExactly("personal-information.json", "social-profile.json");
    }

    @Test
    void forbiddenStopsTheRunAtOnce() throws IOException {
        garmin.respond(PROFILE_PATH, 200, PROFILE_BODY)
                .respond(PERSONAL, 200, LONDON)
                .respond(ACTIVITIES, 200, "[]")
                .respond(ACTIVITIES, 200, "[]")
                .respond(MISSING, 404, "")
                .respond(SUMMARY, 403, "");

        Throwable thrown = catchThrowable(() -> samples.capture(folder));

        assertThat(thrown).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("Capture stopped at summary-today.json.")
                .hasMessageContaining("activity-not-found.json.");
        assertThat(garmin.requests(SUMMARY)).hasSize(1);
        assertThat(garmin.requests(SLEEP)).isEmpty();
        assertThat(files()).hasSize(5).doesNotContain("summary-today.json");
    }

    @Test
    void aServerErrorStopsTheRunWithoutShowingTheToken() {
        garmin.respond(PROFILE_PATH, 503, "{\"error\":\"down\",\"echo\":\"Bearer " + ACCESS_TOKEN + "\"}");

        Throwable thrown = catchThrowable(() -> samples.capture(folder));

        assertThat(thrown).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("HTTP 503").hasMessageContaining("Nothing was saved.")
                .hasMessageNotContaining(ACCESS_TOKEN);
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void anExpiredSessionStopsTheRunWithoutRetrying() {
        garmin.respond(PROFILE_PATH, 200, PROFILE_BODY)
                .respond(PERSONAL, 401, "")
                .respond(TOKEN_PATH, 400, "{\"error\":\"invalid_grant\"}");

        Throwable thrown = catchThrowable(() -> samples.capture(folder));

        assertThat(thrown).isExactlyInstanceOf(GarminException.class).hasCauseInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("Session expired. Run `stride-mcp login`.")
                .hasMessageContaining("Capture stopped at personal-information.json.")
                .hasMessageContaining(": social-profile.json.")
                .hasMessageNotContaining(ACCESS_TOKEN).hasMessageNotContaining(REFRESH_TOKEN);
        assertThat(garmin.requests(PERSONAL)).hasSize(1);
        assertThat(garmin.requests(TOKEN_PATH)).hasSize(1);
    }

    @Test
    void noConnectionStopsTheRun() {
        garmin.close();

        assertThat(catchThrowable(() -> samples.capture(folder))).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("Couldn't reach Garmin")
                .hasMessageContaining("Capture stopped at social-profile.json. Nothing was saved.");
        assertThat(folder).doesNotExist();
    }

    @Test
    void notBeingAbleToStartLeavesNoEmptyFolder() {
        garmin.respond(PROFILE_PATH, 429, "");

        assertThat(catchThrowable(() -> samples.capture(folder))).hasMessageContaining("Nothing was saved.");
        assertThat(folder).doesNotExist();
    }

    @Test
    void aMissingProfileAddressSaysSoInsteadOfBlamingTheDisplayName() {
        garmin.respond(PROFILE_PATH, 404, "");

        assertThat(catchThrowable(() -> samples.capture(folder))).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("Garmin has nothing at the profile address (HTTP 404)")
                .hasMessageContaining("Check for an update to stride-mcp")
                .hasMessageNotContaining("displayName");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void aProfileWithoutADisplayNameStopsTheRun() {
        garmin.respond(PROFILE_PATH, 200, "{\"fullName\":\"Jo Runner\"}");

        assertThat(catchThrowable(() -> samples.capture(folder))).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("no displayName")
                .hasMessageContaining("Capture stopped. Saved before stopping");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void createsAPrivateFolderAndPrivateFiles() throws IOException {
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);
        folder = temp.resolve("new/nested/samples");

        samples.capture(folder);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder, LinkOption.NOFOLLOW_LINKS)))
                .isEqualTo("rwx------");
        assertThat(files()).hasSize(13).allSatisfy(file -> assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(folder.resolve(file), LinkOption.NOFOLLOW_LINKS))).isEqualTo("rw-------"));
    }

    @Test
    void noFileOrNoteEverHoldsAToken() throws IOException {
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);

        Capture capture = samples.capture(folder);

        assertThat(files()).hasSize(13).noneMatch(file -> file.endsWith(".tmp"));
        for (String file : files()) {
            assertThat(Files.readString(folder.resolve(file))).as(file)
                    .doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN).doesNotContain("Bearer")
                    .doesNotContain("eyJ");
        }
        assertThat(String.join("\n", capture.notes())).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN);
    }

    @Test
    void refusesAFolderThatIsASymlinkBeforeCallingGarmin() throws IOException {
        Path real = Files.createDirectory(temp.resolve("real"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(folder, real);

        assertThat(catchThrowable(() -> samples.capture(folder))).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("is a link or not a folder");
        assertThat(garmin.requests()).isEmpty();
        try (Stream<Path> inside = Files.list(real)) {
            assertThat(inside).isEmpty();
        }
    }

    @Test
    void refusesAnExistingFolderOthersCanReadAndLeavesItsPermissionsAlone() throws IOException {
        Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));

        assertThat(catchThrowable(() -> samples.capture(folder))).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("isn't private (700)");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwxr-xr-x");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void refusesAFolderWithSamplesFromAnEarlierRun() throws IOException {
        Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(folder.resolve("activity.json"), "{}");

        assertThat(catchThrowable(() -> samples.capture(folder))).isExactlyInstanceOf(GarminException.class)
                .hasMessageContaining("already holds samples from an earlier run (activity.json)");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void usesAnExistingPrivateFolderWithNoSamplesInIt() throws IOException {
        Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(folder.resolve("notes.txt"), "mine");
        queueRun(LONDON_TODAY, LONDON, TWO_ACTIVITIES);

        assertThat(samples.capture(folder).saved()).hasSize(13);
        assertThat(Files.readString(folder.resolve("notes.txt"))).isEqualTo("mine");
    }

    /** Queues one whole run's replies. Dated replies are matched by query, so their order doesn't matter. */
    private void queueRun(LocalDate today, String personalInformation, String activities) {
        garmin.respond(PROFILE_PATH, 200, PROFILE_BODY)
                .respond(PERSONAL, 200, personalInformation)
                .respond(ACTIVITIES, activitiesQuery("2000-01-01", "2000-01-07"), 200, "[]")
                .respond(ACTIVITIES, activitiesQuery(today.minusDays(6).toString(), today.toString()), 200, activities)
                .respond(ACTIVITY, 200, "{\"activityId\":20123456789,\"summaryDTO\":{\"distance\":8020.0}}")
                .respond(SPLITS, 200, "{\"lapDTOs\":[{\"lapIndex\":1,\"distance\":1000.0}]}")
                .respond(MISSING, 404, "{\"message\":\"Not Found\"}")
                .respond(SUMMARY, Map.of("calendarDate", today.minusDays(30).toString()), 200,
                        "{\"calendarDate\":\"" + today.minusDays(30) + "\",\"totalSteps\":7000}")
                .respond(SUMMARY, Map.of("calendarDate", today.toString()), 200,
                        "{\"calendarDate\":\"" + today + "\",\"totalSteps\":9012}")
                .respond(SLEEP, sleepQuery("2000-01-01"), 204, "")
                .respond(SLEEP, sleepQuery(today.toString()), 200, "{\"dailySleepDTO\":{\"sleepTimeSeconds\":27000}}")
                .respond(weight(today.toString()), 200, "{\"dateWeightList\":[{\"weight\":75400.0}]}")
                .respond(weight("2000-01-01"), 200, "{\"dateWeightList\":[]}");
    }

    private static String weight(String date) {
        return "/weight-service/weight/dayview/" + date;
    }

    private static Map<String, String> activitiesQuery(String from, String to) {
        return Map.of("startDate", from, "endDate", to, "start", "0", "limit", "20");
    }

    private static Map<String, String> sleepQuery(String date) {
        return Map.of("date", date, "nonSleepBufferMinutes", "60");
    }

    private JsonNode read(String file) throws IOException {
        return json.readTree(Files.readString(folder.resolve(file)));
    }

    private List<String> files() throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
