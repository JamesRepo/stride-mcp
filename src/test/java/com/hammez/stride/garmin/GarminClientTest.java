package com.hammez.stride.garmin;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;
import com.hammez.stride.garmin.auth.RateLimitedException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The data calls, parsed from real (stripped) Garmin replies in src/test/resources/garmin, plus made-up variants.
 * The client skips the cache here, so asking twice asks Garmin twice: {@link GarminClientCacheTest} tests the cache.
 */
class GarminClientTest {

    /** 00:30 on Sat 24 October in London (still summer time), but 23:30 on the 23rd in UTC. */
    private static final Instant NOW = Instant.parse("2026-10-23T23:30:00Z");
    private static final LocalDate OCT_23 = LocalDate.of(2026, 10, 23);

    // Decoded, as Garmin sees them. The fixture's display name is "jo runner".
    private static final String PERSONAL = "/userprofile-service/userprofile/personal-information/jo runner";
    private static final String ACTIVITIES = "/activitylist-service/activities/search/activities";
    private static final String ACTIVITY = "/activity-service/activity/24816092240";
    private static final String SPLITS = "/activity-service/activity/24816092240/splits";
    private static final String SUMMARY = "/usersummary-service/usersummary/daily/jo runner";
    private static final String SLEEP = "/wellness-service/wellness/dailySleepData/jo runner";
    private static final String WEIGHT = "/weight-service/weight/dayview/2026-10-23";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private GarminClient client;
    private int clients;

    @BeforeEach
    void setUp() {
        client = client(ZoneOffset.UTC);
    }

    @AfterEach
    void stop() {
        garmin.close();
    }

    // Activities

    @Test
    void activitiesFromARealList() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 200, fixture("activities.json"));

        var activities = client.activities(LocalDate.of(2026, 10, 17), OCT_23, null);

        assertThat(activities).hasSize(8).extracting(ActivitySummary::type).containsExactly(
                "stair_climbing", "indoor_cycling", "running", "strength_training", "strength_training",
                "strength_training", "running", "strength_training");
        assertThat(activities.get(2)).isEqualTo(new ActivitySummary(24810777073L, "Islington Running", "running",
                LocalDateTime.of(2026, 10, 22, 7, 6, 50), Instant.parse("2026-10-22T06:06:50Z"),
                5052.18994140625, 2161.051025390625, 2151.47900390625, 17.80000001192093, 2.3380000591278076,
                143, 169, 448));
        // Strength training: Garmin sends 0.0 for distance and leaves out elevation.
        assertThat(activities.get(3).distanceMetres()).isEqualTo(0.0);
        assertThat(activities.get(3).elevationGainMetres()).isNull();
    }

    @Test
    void activitiesSendsTheRangeAndTheFirstPage() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 200, "[]");

        assertThat(client.activities(LocalDate.of(2026, 10, 17), OCT_23, null)).isEmpty();

        assertThat(garmin.requests(ACTIVITIES)).singleElement().satisfies(r -> assertThat(r.queryParams())
                .isEqualTo(Map.of("startDate", "2026-10-17", "endDate", "2026-10-23", "start", "0", "limit", "20")));
    }

    @Test
    void activitiesSendsTheTypeFilter() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 200, "[]");

        client.activities(OCT_23, OCT_23, "running");

        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams()).containsEntry("activityType", "running");
    }

    @Test
    void activitiesWithNoContentIsAnEmptyList() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 204, "");

        assertThat(client.activities(OCT_23, OCT_23, null)).isEmpty();
    }

    @Test
    void activitiesMissingFieldsBecomeNull() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 200, "[{\"activityId\":5,\"averageHR\":\"fast\"}]");

        assertThat(client.activities(OCT_23, OCT_23, null)).containsExactly(new ActivitySummary(5, null, null,
                null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void activitiesPagesUntilAPageIsNotFull() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, Map.of("startDate", "2026-10-23", "endDate", "2026-10-23", "start", "0",
                        "limit", "20"), 200, page(1, 20))
                .respond(ACTIVITIES, Map.of("startDate", "2026-10-23", "endDate", "2026-10-23", "start", "20",
                        "limit", "20"), 200, page(21, 3));

        assertThat(client.activities(OCT_23, OCT_23, null)).extracting(ActivitySummary::id)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 23).mapToObj(i -> (long) i).toList());
        assertThat(garmin.requests(ACTIVITIES)).hasSize(2);
    }

    @Test
    void activitiesStopsAtThePageLimit() {
        signedInAs("{}");
        for (int i = 0; i < 11; i++) {
            garmin.respond(ACTIVITIES, 200, page(i * 20 + 1, 20));
        }

        assertThat(catchThrowable(() -> client.activities(OCT_23, OCT_23, null)))
                .isExactlyInstanceOf(GarminException.class)
                .hasMessage("More than 200 activities from 2026-10-23 to 2026-10-23. Try a shorter range.");
        assertThat(garmin.requests(ACTIVITIES)).hasSize(10);
    }

    @Test
    void activitiesAllowsTheLongestRange() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 200, "[]");

        assertThat(client.activities(LocalDate.of(2026, 7, 26), OCT_23, null)).isEmpty();

        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams()).containsEntry("startDate", "2026-07-26");
    }

    @Test
    void activitiesRefusesARangeOverTheLimit() {
        assertThat(catchThrowable(() -> client.activities(LocalDate.of(2026, 7, 25), OCT_23, null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("from 2026-07-25 to 2026-10-23 is more than 90 days. Ask for at most 90 days, "
                        + "both ends included.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void activitiesRefusesFromAfterTo() {
        assertThat(catchThrowable(() -> client.activities(OCT_23, LocalDate.of(2026, 10, 22), null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("from 2026-10-23 is after to 2026-10-22. Put the earlier date first.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void activitiesThatArentAListAreAnUnexpectedReply() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 200, "{\"activities\":[]}")
                .respond(ACTIVITIES, 200, "[{\"activityName\":\"No id\"}]")
                .respond(ACTIVITIES, 200, "<html>Maintenance</html>")
                .respond(ACTIVITIES, 200, "[{\"activityId\":1,\"startTimeLocal\":\"yesterday\"}]");

        for (int i = 0; i < 4; i++) {
            assertThat(catchThrowable(() -> client.activities(OCT_23, OCT_23, null)))
                    .isExactlyInstanceOf(UnexpectedReplyException.class)
                    .hasMessage("Garmin's reply for the activities from 2026-10-23 to 2026-10-23 wasn't what "
                            + "stride-mcp expected. Garmin may have changed it. Check for an update to stride-mcp.");
        }
    }

    // One activity

    @Test
    void activityFromARealReply() {
        garmin.respond(ACTIVITY, 200, fixture("activity.json")).respond(SPLITS, 200, fixture("activity-splits.json"));

        ActivityDetails details = client.activity(24816092240L);

        assertThat(details).isEqualTo(new ActivityDetails(
                new ActivitySummary(24816092240L, "Stair Stepper", "stair_climbing",
                        LocalDateTime.of(2026, 10, 22, 17, 4, 4), Instant.parse("2026-10-22T16:04:04Z"),
                        0.0, 602.018, 0.0, null, 0.0, 142, 158, 133),
                null, null, null, 2.0, 0.0, 21.563720703125,
                List.of(new Lap(1, 0.0, 602.018, 0.0, 142, 158, null))));
    }

    @Test
    void activityRunFieldsMadeUp() {
        // Made up: the sample was a stair stepper session, so these names are still to confirm with a run.
        garmin.respond(ACTIVITY, 200, """
                {"activityId":24816092240,"activityTypeDTO":{"typeKey":"running"},
                 "summaryDTO":{"maxSpeed":3.1,"averageRunCadence":162.5,"averagePower":301.6,"trainingEffect":3.2,
                 "elevationGain":12.0}}""")
                .respond(SPLITS, 200, """
                        {"lapDTOs":[{"lapIndex":1,"distance":1000.0,"elevationGain":4.0},
                         {"distance":52.2}]}""");

        ActivityDetails details = client.activity(24816092240L);

        assertThat(details.maxSpeedMetresPerSecond()).isEqualTo(3.1);
        assertThat(details.averageCadenceStepsPerMinute()).isEqualTo(162.5);
        assertThat(details.averagePowerWatts()).isEqualTo(302);
        assertThat(details.aerobicTrainingEffect()).isEqualTo(3.2);
        assertThat(details.summary().elevationGainMetres()).isEqualTo(12.0);
        assertThat(details.laps()).extracting(Lap::number).containsExactly(1, 2);
        assertThat(details.laps().getFirst().elevationGainMetres()).isEqualTo(4.0);
    }

    @Test
    void activityAcceptsTheListsNamesForCadenceAndPower() {
        garmin.respond(ACTIVITY, 200, """
                {"activityId":24816092240,"summaryDTO":{"averageRunningCadenceInStepsPerMinute":153.2,"avgPower":319.0,
                 "aerobicTrainingEffect":2.5}}""")
                .respond(SPLITS, 200, "{}");

        ActivityDetails details = client.activity(24816092240L);

        assertThat(details.averageCadenceStepsPerMinute()).isEqualTo(153.2);
        assertThat(details.averagePowerWatts()).isEqualTo(319);
        assertThat(details.aerobicTrainingEffect()).isEqualTo(2.5);
    }

    @Test
    void activityWithNoLaps() {
        garmin.respond(ACTIVITY, 200, fixture("activity.json")).respond(SPLITS, 200, "{\"lapDTOs\":[]}");
        assertThat(client.activity(24816092240L).laps()).isEmpty();

        garmin.respond(ACTIVITY, 200, fixture("activity.json")).respond(SPLITS, 204, "");
        assertThat(client.activity(24816092240L).laps()).isEmpty();
    }

    @Test
    void activityNotFound() {
        garmin.respond("/activity-service/activity/1", 404, "{\"message\":\"Not Found\"}");

        assertThat(catchThrowable(() -> client.activity(1))).isInstanceOf(NotFoundException.class)
                .hasMessage("No activity with id 1. Check the id in `stride-mcp activities`.");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void activityWithoutTotalsIsAnUnexpectedReply() {
        garmin.respond(ACTIVITY, 200, "{\"activityId\":24816092240}").respond(SPLITS, 200, "{}");

        assertThat(catchThrowable(() -> client.activity(24816092240L)))
                .isExactlyInstanceOf(UnexpectedReplyException.class).hasMessageContaining("activity 24816092240");
    }

    // Daily summary

    @Test
    void dailySummaryFromARealReply() {
        signedInAs("{\"timeZone\":\"Europe/London\"}");
        garmin.respond(SUMMARY, Map.of("calendarDate", "2026-10-23"), 200, fixture("summary-today.json"));

        assertThat(client.dailySummary(OCT_23)).isEqualTo(new DailySummary(OCT_23, 3532, 11190, 3182.0, 1417, 222,
                45, 43, 130, 20, 96, 30, 0, 2, 7.13878));
    }

    @Test
    void dailySummaryForADayWithNoData() {
        signedInAs("{}");
        garmin.respond(SUMMARY, 200, "{\"calendarDate\":\"2000-01-01\",\"privacyProtected\":false,\"totalSteps\":null,"
                        + "\"averageStressLevel\":-1}")
                .respond(SUMMARY, 204, "");

        var empty = new DailySummary(LocalDate.of(2000, 1, 1), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        assertThat(client.dailySummary(LocalDate.of(2000, 1, 1))).isEqualTo(empty);
        assertThat(client.dailySummary(LocalDate.of(2000, 1, 1))).isEqualTo(empty);
    }

    @Test
    void dailySummaryThatGarminWontShare() {
        signedInAs("{}");
        garmin.respond(SUMMARY, 200, "{\"privacyProtected\":true}");

        assertThat(catchThrowable(() -> client.dailySummary(OCT_23))).isExactlyInstanceOf(GarminException.class)
                .hasMessage("Garmin wouldn't share the summary for 2026-10-23. Run `stride-mcp login` again.");
    }

    // Sleep

    @Test
    void sleepFromARealReply() {
        signedInAs("{}");
        garmin.respond(SLEEP, Map.of("date", "2026-10-23", "nonSleepBufferMinutes", "60"), 200,
                fixture("sleep-last-night.json"));

        assertThat(client.sleep(OCT_23)).contains(new Sleep(OCT_23,
                Instant.ofEpochMilli(1792705028000L), Instant.ofEpochMilli(1792737428000L),
                31920, 5460, 17700, 8760, 480, 91, "EXCELLENT", 12.0, 97.0));
    }

    @Test
    void noSleepIsEmpty() {
        signedInAs("{}");
        // Real: Garmin answers 200 with a dailySleepDTO full of nulls.
        garmin.respond(SLEEP, 200, fixture("sleep-none.json"))
                .respond(SLEEP, 204, "")
                .respond(SLEEP, 200, "{\"dailySleepDTO\":null}");

        for (int i = 0; i < 3; i++) {
            assertThat(client.sleep(LocalDate.of(2000, 1, 1))).isEmpty();
        }
    }

    @Test
    void sleepThatIsntAnObjectIsAnUnexpectedReply() {
        signedInAs("{}");
        garmin.respond(SLEEP, 200, "[]").respond(SLEEP, 200, "{\"dailySleepDTO\":\"none\"}");

        for (int i = 0; i < 2; i++) {
            assertThat(catchThrowable(() -> client.sleep(OCT_23))).isExactlyInstanceOf(UnexpectedReplyException.class)
                    .hasMessageContaining("the sleep for the night ending 2026-10-23");
        }
    }

    // Weigh-ins

    @Test
    void weighInsOldestFirstInKilograms() {
        signedInAs("{}");
        garmin.respond(WEIGHT, Map.of("includeAll", "true"), 200, Fixtures.TWO_WEIGH_INS);

        assertThat(client.weighIns(OCT_23)).containsExactly(
                new WeighIn(OCT_23, Instant.ofEpochMilli(1792737600000L), 75.9, null, null, null, null, null, null,
                        null),
                new WeighIn(OCT_23, Instant.ofEpochMilli(1792737900000L), 75.4, 23.27, 18.6, 55.1, 33.1, 3.2, null,
                        null));
    }

    @Test
    void weighInsWithoutAnExactTimeAreSortedByTheClockTime() {
        signedInAs("{}");
        // "date" is the time on the clock, in epoch milliseconds: enough to order one day's weigh-ins.
        garmin.respond(WEIGHT, 200, """
                {"dateWeightList":[{"weight":75900.0,"date":1792760400000},{"weight":75400.0,"date":1792738800000},
                 {"weight":76100.0}]}""");

        List<WeighIn> weighIns = client.weighIns(OCT_23);

        assertThat(weighIns).extracting(WeighIn::weightKilograms).containsExactly(76.1, 75.4, 75.9);
        assertThat(weighIns).extracting(WeighIn::time).containsOnlyNulls();
    }

    @Test
    void weighInValuesThatDontMakeSenseAreLeftOut() {
        signedInAs("{}");
        garmin.respond(WEIGHT, 200, """
                {"dateWeightList":[{"weight":"heavy","bodyFat":-1.0,"bodyWater":140.0,"visceralFat":6.0,
                 "metabolicAge":34}]}""");

        assertThat(client.weighIns(OCT_23)).containsExactly(
                new WeighIn(OCT_23, null, null, null, null, null, null, null, 6, 34));
    }

    @Test
    void noWeighInsIsEmpty() {
        signedInAs("{}");
        // Real: Garmin answers 200 with an empty dateWeightList and an all-null totalAverage.
        garmin.respond(WEIGHT, 200, fixture("weight-none.json"))
                .respond(WEIGHT, 204, "")
                .respond(WEIGHT, 200, "{\"dateWeightList\":null}")
                .respond(WEIGHT, 200, "{}");

        for (int i = 0; i < 4; i++) {
            assertThat(client.weighIns(OCT_23)).isEmpty();
        }
    }

    @Test
    void weighInsThatArentAListAreAnUnexpectedReply() {
        signedInAs("{}");
        garmin.respond(WEIGHT, 200, "[]")
                .respond(WEIGHT, 200, "{\"dateWeightList\":\"none\"}")
                .respond(WEIGHT, 200, "{\"dateWeightList\":[75400]}");

        for (int i = 0; i < 3; i++) {
            assertThat(catchThrowable(() -> client.weighIns(OCT_23)))
                    .isExactlyInstanceOf(UnexpectedReplyException.class)
                    .hasMessageContaining("the weigh-ins for 2026-10-23");
        }
    }

    // Dates and the profile

    @Test
    void todayIsInTheProfilesTimeZone() {
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json"))
                .respond(PERSONAL, 200, fixture("personal-information.json"));

        assertThat(client.today()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(client.zone()).isEqualTo(ZoneId.of("Europe/London"));
    }

    @Test
    void todayFallsBackToTheComputersTimeZone() {
        client = client(ZoneId.of("America/Los_Angeles"));
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json"))
                .respond(PERSONAL, 200, "{\"timeZone\":\"Not/AZone\"}");
        assertThat(client.today()).isEqualTo(OCT_23);

        client = client(ZoneId.of("America/Los_Angeles"));
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json")).respond(PERSONAL, 404, "");
        assertThat(client.today()).isEqualTo(OCT_23);
    }

    @Test
    void theProfileIsFetchedOnceEvenWhenSkippingTheCacheAndTheDisplayNameIsEncoded() {
        signedInAs("{}");
        garmin.respond(SUMMARY, 200, "{}").respond(SUMMARY, 200, "{}").respond(SLEEP, 204, "");

        client.dailySummary(OCT_23);
        client.dailySummary(OCT_23.minusDays(1));
        client.sleep(OCT_23);
        client.today();

        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
        assertThat(garmin.requests(PERSONAL)).hasSize(1);
        assertThat(garmin.requests(SUMMARY)).hasSize(2);
    }

    @Test
    void aProfileWithoutADisplayNameIsAnUnexpectedReply() {
        garmin.respond(PROFILE_PATH, 200, "{\"fullName\":\"Jo\"}");

        assertThat(catchThrowable(() -> client.dailySummary(OCT_23)))
                .isExactlyInstanceOf(UnexpectedReplyException.class).hasMessageContaining("your profile");
        assertThat(garmin.requests(SUMMARY)).isEmpty();
    }

    @Test
    void garminErrorsPassThrough() {
        signedInAs("{}");
        garmin.respond(ACTIVITIES, 429, "");

        assertThat(catchThrowable(() -> client.activities(OCT_23, OCT_23, null)))
                .isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests(ACTIVITIES)).hasSize(1);
    }

    /** A client skipping the cache, with a cache folder of its own, so no profile is carried over. */
    private GarminClient client(ZoneId fallbackZone) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JsonMapper json = JsonMapper.builder().build();
        return new GarminClient(garmin.signedInApi(temp.resolve("garmin_tokens.json"), clock),
                new GarminCache(temp.resolve("cache-" + ++clients), json, clock),
                GarminDataConfig.defaults(garmin.config()), json, clock, fallbackZone).skippingCache();
    }

    private void signedInAs(String personalInformation) {
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json")).respond(PERSONAL, 200, personalInformation);
    }

    /** A full or partial page of made-up activities with ids first, first + 1… */
    private static String page(int first, int count) {
        return IntStream.range(first, first + count).mapToObj(id -> "{\"activityId\":" + id + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String fixture(String name) {
        return Fixtures.read(name);
    }
}
