package com.hammez.stride.garmin;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.RateLimitedException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The daily log (phase 4b) and the range calls it makes for steps and sleep, from the real (stripped) replies of
 * 20 November 2026 in src/test/resources/garmin, plus made-up variants. The client skips the cache here, except for the
 * activity types, which it always takes from the cache: {@link GarminClientCacheTest} tests the cache.
 * <p>
 * The profile's time zone is Europe/London, and it's the evening of 20 November, when the capture was made. The
 * fixtures' range, 24 October to 20 November, is 28 days, the most allowed. The clocks went back on 25 October.
 */
class GarminClientDailyLogTest {

    private static final Instant NOW = Instant.parse("2026-11-20T22:38:00Z");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate OCT_24 = LocalDate.of(2026, 10, 24);
    private static final LocalDate OCT_25 = LocalDate.of(2026, 10, 25);
    private static final LocalDate OCT_26 = LocalDate.of(2026, 10, 26);
    private static final LocalDate NOV_12 = LocalDate.of(2026, 11, 12);
    private static final LocalDate NOV_13 = LocalDate.of(2026, 11, 13);
    private static final LocalDate NOV_14 = LocalDate.of(2026, 11, 14);
    private static final LocalDate NOV_19 = LocalDate.of(2026, 11, 19);
    private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);

    // Decoded, as Garmin sees them. The fixture's display name is "jo runner".
    private static final String PERSONAL = "/userprofile-service/userprofile/personal-information/jo runner";
    private static final String STEPS = "/usersummary-service/v2/dailysummaries";
    private static final String SLEEP = "/sleep-service/stats/sleep/daily/2026-10-24/2026-11-20";
    private static final String WEIGHT = "/weight-service/weight/range/2026-10-24/2026-11-20";
    private static final String ACTIVITIES = "/activitylist-service/activities/search/activities";
    private static final String TYPES = "/activity-service/activity/activityTypes";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private GarminClient client;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JsonMapper json = JsonMapper.builder().build();
        client = new GarminClient(garmin.signedInApi(temp.resolve("garmin_tokens.json"), clock),
                new GarminCache(temp.resolve("cache"), json, clock),
                GarminDataConfig.defaults(garmin.config()), json, clock, ZoneOffset.UTC).skippingCache();
    }

    @AfterEach
    void stop() {
        garmin.close();
    }

    // Steps over a range

    @Test
    void stepsFromARealReplyOldestFirst() {
        signedIn();
        garmin.respond(STEPS, Map.of("startDate", "2026-10-24", "endDate", "2026-11-20"), 200,
                fixture("summary-range.json"));

        assertThat(client.dailyTotals(OCT_24, NOV_20)).containsExactly(
                new DayTotals(OCT_24, 9013, 3226, midnightAfter(OCT_24)),
                // The clock change: 25 hours long.
                new DayTotals(OCT_25, 13768, 2556, midnightAfter(OCT_25)),
                // Nothing logged in MyFitnessPal: consumedValue is null.
                new DayTotals(NOV_12, 14239, null, midnightAfter(NOV_12)),
                new DayTotals(NOV_19, 6352, 2667, midnightAfter(NOV_19)),
                // Today, as far as the watch had synced (08:42).
                new DayTotals(NOV_20, 95, 2442, LocalDateTime.of(2026, 11, 20, 8, 42)));
        assertThat(garmin.requests(STEPS)).hasSize(1);
    }

    @Test
    void caloriesEatenMissingNullOrZeroAreLeftOut() {
        signedIn();
        garmin.respond(STEPS, 200, """
                {"data":[
                 {"calendarDate":"2026-11-12","movement":{"steps":{"value":100}},"calories":{"consumedValue":0}},
                 {"calendarDate":"2026-11-13","movement":{"steps":{"value":200}},"calories":{"consumedValue":null}},
                 {"calendarDate":"2026-11-14","movement":{"steps":{"value":300}},"calories":{"burnedTotal":2900}},
                 {"calendarDate":"2026-11-15","movement":{"steps":{"value":400}}},
                 {"calendarDate":"2026-11-16","movement":{"steps":{"value":500}},"calories":{"consumedValue":"lots"}},
                 {"calendarDate":"2026-11-17","calories":{"consumedValue":1850.0}}]}""");

        assertThat(client.dailyTotals(OCT_24, NOV_20)).containsExactly(
                new DayTotals(NOV_12, 100, null, null),
                new DayTotals(NOV_13, 200, null, null),
                new DayTotals(NOV_14, 300, null, null),
                new DayTotals(LocalDate.of(2026, 11, 15), 400, null, null),
                new DayTotals(LocalDate.of(2026, 11, 16), 500, null, null),
                // Calories eaten, but no steps.
                new DayTotals(LocalDate.of(2026, 11, 17), null, 1850, null));
    }

    @Test
    void aDayWithNothingOrOutsideTheRangeIsLeftOut() {
        signedIn();
        // Garmin hasn't been seen to do these. A day with its values null is "Garmin has nothing".
        garmin.respond(STEPS, 200, """
                {"data":[
                 {"calendarDate":"2026-10-23","movement":{"steps":{"value":1}}},
                 {"calendarDate":"2026-11-12","movement":{"steps":{"value":null}},"calories":{"consumedValue":null}},
                 {"calendarDate":"2026-11-13","movement":null,"calories":null},
                 {"calendarDate":"2026-11-14"},
                 {"calendarDate":"2026-11-20","movement":{"steps":{"value":95}}},
                 {"calendarDate":"2026-11-21","movement":{"steps":{"value":2}}}]}""");

        assertThat(client.dailyTotals(OCT_24, NOV_20)).containsExactly(new DayTotals(NOV_20, 95, null, null));
    }

    @Test
    void aDayGarminWontShareIsRefusedNotLeftBlank() {
        signedIn();
        // Not seen in a range reply, but the one-day reply can have it.
        garmin.respond(STEPS, 200, """
                {"data":[{"calendarDate":"2026-11-11","movement":{"steps":{"value":7000}}},
                 {"calendarDate":"2026-11-12","privacyProtected":true}]}""");

        assertThat(catchThrowable(() -> client.dailyTotals(OCT_24, NOV_20)))
                .isExactlyInstanceOf(GarminException.class)
                .hasMessage("Garmin wouldn't share the summary for 2026-11-12. Run `stride-mcp login` again.");
    }

    @Test
    void aDayGarminWontShareOutsideTheRangeIsLeftOutAndFalseIsFine() {
        signedIn();
        garmin.respond(STEPS, 200, """
                {"data":[{"calendarDate":"2026-10-23","privacyProtected":true},
                 {"calendarDate":"2026-11-12","privacyProtected":false,"movement":{"steps":{"value":14239}}}]}""");

        assertThat(client.dailyTotals(OCT_24, NOV_20)).containsExactly(new DayTotals(NOV_12, 14239, null, null));
    }

    @Test
    void noStepsIsEmpty() {
        signedIn();
        garmin.respond(STEPS, 200, "{\"data\":[]}")
                .respond(STEPS, 204, "")
                .respond(STEPS, 200, "{\"data\":null}")
                .respond(STEPS, 200, "{}");

        for (int i = 0; i < 4; i++) {
            assertThat(client.dailyTotals(OCT_24, NOV_20)).isEmpty();
        }
    }

    @Test
    void stepsThatDontReadAreAnUnexpectedReply() {
        signedIn();
        garmin.respond(STEPS, 200, "[]")
                .respond(STEPS, 200, "{\"data\":\"none\"}")
                .respond(STEPS, 200, "{\"data\":[95]}")
                .respond(STEPS, 200, "{\"data\":[{\"movement\":{}}]}")
                .respond(STEPS, 200, "{\"data\":[{\"calendarDate\":\"20 Nov\"}]}");

        for (int i = 0; i < 5; i++) {
            assertThat(catchThrowable(() -> client.dailyTotals(OCT_24, NOV_20)))
                    .isExactlyInstanceOf(UnexpectedReplyException.class)
                    .hasMessageContaining("the daily summaries from 2026-10-24 to 2026-11-20");
        }
    }

    // Sleep over a range

    @Test
    void sleepFromARealReplyOldestFirst() {
        signedIn();
        garmin.respond(SLEEP, Map.of(), 200, fixture("sleep-range.json"));

        List<Sleep> nights = client.sleep(OCT_24, NOV_20);

        assertThat(nights).extracting(Sleep::date).containsExactly(OCT_24, OCT_25, NOV_12, NOV_19, NOV_20);
        assertThat(nights).extracting(Sleep::score).containsExactly(75, 82, 50, 49, 95);
        // As the log has it: score 50, 6.6 hours. No deep sleep at all that night.
        assertThat(nights.get(2)).isEqualTo(new Sleep(NOV_12, Instant.parse("2026-11-12T00:41:48Z"),
                Instant.parse("2026-11-12T07:36:48Z"), 23580, 0, 21300, 2280, 1320, 50, "POOR", 12.6, 96.17));
        // The night ending on the 20th started on the evening of the 19th.
        assertThat(nights.getLast().start()).isEqualTo(Instant.parse("2026-11-19T22:34:31Z"));
        assertThat(nights.getLast().sleepSeconds()).isEqualTo(36000);
        assertThat(nights.getLast().quality()).isEqualTo("EXCELLENT");
        assertThat(garmin.requests(SLEEP)).hasSize(1);
    }

    @Test
    void aNightWithNoSleepOrOutsideTheRangeIsLeftOut() {
        signedIn();
        garmin.respond(SLEEP, 200, """
                {"overallStats":{},"individualStats":[
                 {"calendarDate":"2026-10-23","values":{"totalSleepTimeInSeconds":1}},
                 {"calendarDate":"2026-11-12","values":null},
                 {"calendarDate":"2026-11-13"},
                 {"calendarDate":"2026-11-14","values":{}},
                 {"calendarDate":"2026-11-15","values":{"totalSleepTimeInSeconds":null,"gmtSleepStartTimeInMillis":null,
                  "sleepScore":null}},
                 {"calendarDate":"2026-11-20","values":{"totalSleepTimeInSeconds":36000,"sleepScore":"high"}},
                 {"calendarDate":"2026-11-21","values":{"totalSleepTimeInSeconds":2}}]}""");

        assertThat(client.sleep(OCT_24, NOV_20)).containsExactly(
                new Sleep(NOV_20, null, null, 36000, null, null, null, null, null, null, null, null));
    }

    @Test
    void noSleepIsEmpty() {
        signedIn();
        garmin.respond(SLEEP, 200, "{\"overallStats\":{},\"individualStats\":[]}")
                .respond(SLEEP, 204, "")
                .respond(SLEEP, 200, "{\"individualStats\":null}")
                .respond(SLEEP, 200, "{}");

        for (int i = 0; i < 4; i++) {
            assertThat(client.sleep(OCT_24, NOV_20)).isEmpty();
        }
    }

    @Test
    void sleepThatDoesntReadIsAnUnexpectedReply() {
        signedIn();
        garmin.respond(SLEEP, 200, "[]")
                .respond(SLEEP, 200, "{\"individualStats\":{}}")
                .respond(SLEEP, 200, "{\"individualStats\":[{\"values\":{}}]}")
                .respond(SLEEP, 200, "{\"individualStats\":[{\"calendarDate\":\"2026-11-20\",\"values\":[]}]}");

        for (int i = 0; i < 4; i++) {
            assertThat(catchThrowable(() -> client.sleep(OCT_24, NOV_20)))
                    .isExactlyInstanceOf(UnexpectedReplyException.class)
                    .hasMessageContaining("the sleep for the nights ending 2026-10-24 to 2026-11-20");
        }
    }

    @Test
    void stepsAndSleepRefuseMoreThan28DaysOrABackwardsRangeAndSendNothing() {
        LocalDate tooEarly = NOV_20.minusDays(GarminDataConfig.MAX_LOG_RANGE_DAYS);

        assertThat(catchThrowable(() -> client.dailyTotals(tooEarly, NOV_20)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than 28 days");
        assertThat(catchThrowable(() -> client.sleep(tooEarly, NOV_20)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than 28 days");
        assertThat(catchThrowable(() -> client.dailyTotals(NOV_20, NOV_19)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessageContaining("Put the earlier date first");
        assertThat(catchThrowable(() -> client.sleep(NOV_20, NOV_19)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessageContaining("Put the earlier date first");
        assertThat(garmin.requests()).isEmpty();
    }

    // Activity types

    @Test
    void groupsFromTheRealListOfActivityTypes() {
        garmin.respond(TYPES, 200, fixture("activity-types.json"));

        ActivityTypes types = client.activityTypes();

        // Everything below running, and running itself.
        for (String run : List.of("running", "trail_running", "street_running", "track_running",
                "treadmill_running", "virtual_run", "obstacle_run", "indoor_running", "ultra_run", "canicross")) {
            assertThat(types.group(run)).as(run).isEqualTo("running");
        }
        // The owner's other types.
        assertThat(types.group("indoor_cycling")).isEqualTo("cycling");
        assertThat(types.group("strength_training")).isEqualTo("fitness_equipment");
        assertThat(types.group("stair_climbing")).isEqualTo("fitness_equipment");
        assertThat(types.group("soccer")).isEqualTo("team_sports");
        // The root has no group, and nor does a type Garmin doesn't list.
        assertThat(types.group("all")).isNull();
        assertThat(types.group("underwater_hockey_2030")).isNull();
        assertThat(types.group(null)).isNull();
        // Every type but the root has a group.
        assertThat(types.groups()).hasSize(155);
        assertThat(types.listed()).hasSize(156);
        assertThat(types.lists("all")).isTrue();
        assertThat(types.lists("underwater_hockey_2030")).isFalse();
        assertThat(types.lists(null)).isFalse();
    }

    @Test
    void aTypeWhoseParentsCantBeFollowedToTheRootHasNoGroup() {
        // Made up: a parent that isn't in the list, and two types that are each other's parent.
        garmin.respond(TYPES, 200, """
                [{"typeId":17,"typeKey":"all","parentTypeId":null},
                 {"typeId":1,"typeKey":"running","parentTypeId":17},
                 {"typeId":6,"typeKey":"trail_running","parentTypeId":1},
                 {"typeId":7,"typeKey":"orphan","parentTypeId":999},
                 {"typeId":8,"typeKey":"ping","parentTypeId":9},
                 {"typeId":9,"typeKey":"pong","parentTypeId":8},
                 {"typeKey":"no_id","parentTypeId":17},
                 {"typeId":10,"parentTypeId":17}]""");

        ActivityTypes types = client.activityTypes();

        assertThat(types.groups()).isEqualTo(Map.of("running", "running", "trail_running", "running"));
        // Listed, even without a group, so they aren't a reason to ask for the list again.
        assertThat(types.listed()).containsExactlyInAnyOrder("all", "running", "trail_running", "orphan", "ping",
                "pong");
    }

    @Test
    void activityTypesThatDontReadAreAnUnexpectedReply() {
        garmin.respond(TYPES, 200, "{}")
                .respond(TYPES, 200, "[]")
                .respond(TYPES, 204, "")
                .respond(TYPES, 200, "[{\"typeKey\":\"running\"}]");

        for (int i = 0; i < 4; i++) {
            assertThat(catchThrowable(() -> client.activityTypes()))
                    .isExactlyInstanceOf(UnexpectedReplyException.class)
                    .hasMessageContaining("Garmin's list of activity types");
        }
    }

    // The daily log

    @Test
    void dailyLogFromTheRealRepliesHasEveryDayOldestFirst() {
        signedIn();
        garmin.respond(STEPS, 200, fixture("summary-range.json"))
                .respond(SLEEP, 200, fixture("sleep-range.json"))
                .respond(WEIGHT, Map.of("includeAll", "true"), 200, fixture("weight-range.json"))
                .respond(ACTIVITIES, Map.of("startDate", "2026-10-23", "endDate", "2026-11-21", "start", "0",
                        "limit", "20"), 200, "[" + activity(1, "London Running", "running",
                        "2026-11-12T18:00:00Z", 2994) + "]")
                .respond(TYPES, 200, fixture("activity-types.json"));

        List<LogDay> log = client.dailyLog(OCT_24, NOV_20);

        assertThat(log).hasSize(28).extracting(LogDay::date)
                .containsExactlyElementsOf(OCT_24.datesUntil(NOV_20.plusDays(1)).toList());
        LogDay nov12 = log.get(19);
        assertThat(nov12.date()).isEqualTo(NOV_12);
        assertThat(nov12.isFinal()).isTrue();
        assertThat(nov12.sleep().score()).isEqualTo(50);
        assertThat(nov12.sleep().sleepSeconds()).isEqualTo(23580);
        assertThat(nov12.steps()).isEqualTo(14239);
        assertThat(nov12.caloriesEaten()).isNull();
        assertThat(nov12.activities()).singleElement().satisfies(a -> {
            assertThat(a.activity().name()).isEqualTo("London Running");
            assertThat(a.group()).isEqualTo("running");
        });
        assertThat(nov12.weight()).isNull();
        // A day none of the trimmed fixtures kept: Garmin has nothing for it here.
        assertThat(log.get(20))
                .isEqualTo(new LogDay(NOV_13, LogDay.State.FINAL, null, null, null, null, List.of(), null));
        // Two weights typed in: the first counts, as in 4a.
        LogDay nov14 = log.get(21);
        assertThat(nov14.weight().weight().weightKilograms()).isEqualTo(75.8);
        assertThat(nov14.weight().weighIns()).isEqualTo(2);
        // Today: a part day.
        LogDay today = log.getLast();
        assertThat(today.isFinal()).isFalse();
        assertThat(today.steps()).isEqualTo(95);
        assertThat(today.caloriesEaten()).isEqualTo(2442);
        assertThat(today.sleep().score()).isEqualTo(95);
        assertThat(today.weight().weight().weightKilograms()).isEqualTo(76.1);
        assertThat(log.get(26).isFinal()).isTrue();
        // One request of each kind for 28 days.
        assertThat(garmin.requests(STEPS)).hasSize(1);
        assertThat(garmin.requests(SLEEP)).hasSize(1);
        assertThat(garmin.requests(WEIGHT)).hasSize(1);
        assertThat(garmin.requests(ACTIVITIES)).hasSize(1);
        assertThat(garmin.requests(TYPES)).hasSize(1);
    }

    @Test
    void theClockChangeDayHasItsStepsAndAllItsActivities() {
        signedIn();
        garmin.respond(STEPS, Map.of("startDate", "2026-10-24", "endDate", "2026-10-26"), 200,
                fixture("summary-range.json"));
        nothingButActivities(OCT_24, OCT_26, "[" + String.join(",",
                // 00:30 GMT on the 26th, the first activity of the next day.
                activity(5, "Next day", "running", "2026-10-26T00:30:00Z", 600),
                // 23:30 GMT on the 25th.
                activity(4, "Late", "running", "2026-10-25T23:30:00Z", 600),
                // 01:30 GMT, the second time the clock showed 01:30 that night.
                activity(3, "Second 01:30", "running", "2026-10-25T01:30:00Z", 600),
                // 01:30 BST, the first time.
                activity(2, "First 01:30", "running", "2026-10-25T00:30:00Z", 600)) + "]");

        List<LogDay> log = client.dailyLog(OCT_24, OCT_26);

        assertThat(log.get(1).date()).isEqualTo(OCT_25);
        assertThat(log.get(1).steps()).isEqualTo(13768);
        // 25 hours of watch data, to midnight: final.
        assertThat(log.get(1).isFinal()).isTrue();
        assertThat(log.get(1).watchDataUntil()).isEqualTo(midnightAfter(OCT_25));
        assertThat(log.get(1).activities()).extracting(a -> a.activity().name())
                .containsExactly("First 01:30", "Second 01:30", "Late");
        assertThat(log.get(2).activities()).extracting(a -> a.activity().name()).containsExactly("Next day");
    }

    @Test
    void anActivityJustAfterMidnightIsOnThatDayInTheOwnersTimeZone() {
        signedIn();
        // 24 October is still summer time: 00:05 in London is 23:05 the day before in UTC.
        nothingButActivities(OCT_24, OCT_26, "[" + String.join(",",
                activity(2, "Just after midnight", "running", "2026-10-24T23:05:00Z", 600),
                activity(1, "Just before midnight", "running", "2026-10-24T22:55:00Z", 600)) + "]");

        List<LogDay> log = client.dailyLog(OCT_24, OCT_26);

        assertThat(log.get(0).activities()).extracting(a -> a.activity().name())
                .containsExactly("Just before midnight");
        assertThat(log.get(1).activities()).extracting(a -> a.activity().name())
                .containsExactly("Just after midnight");
    }

    @Test
    void aDayWithThreeActivitiesHasThemOldestFirstEachWithItsGroup() {
        signedIn();
        // Garmin lists them newest first.
        nothingButActivities(NOV_12, NOV_12, "[" + String.join(",",
                activity(3, "Spin", "indoor_cycling", "2026-11-12T18:00:00Z", 2700),
                activity(2, "Gym", "strength_training", "2026-11-12T12:30:00Z", 3600),
                activity(1, "Treadmill", "treadmill_running", "2026-11-12T07:00:00Z", 1800)) + "]");

        LogDay day = client.dailyLog(NOV_12, NOV_12).getFirst();

        assertThat(day.activities()).extracting(a -> a.activity().name(), LogDay.Activity::group).containsExactly(
                tuple("Treadmill", "running"),
                tuple("Gym", "fitness_equipment"),
                tuple("Spin", "cycling"));
        assertThat(day.activities().getFirst().activity().durationSeconds()).isEqualTo(1800.0);
    }

    @Test
    void aTypeNotInTheListGetsNoGroupEvenAfterAskingAgain() {
        signedIn();
        nothingButActivities(NOV_12, NOV_12, "[" + String.join(",",
                activity(2, "Something new", "underwater_hockey_2030", "2026-11-12T18:00:00Z", 600),
                "{\"activityId\":1,\"startTimeGMT\":\"2026-11-12 07:00:00\"}") + "]");
        garmin.respond(TYPES, 200, fixture("activity-types.json"));

        LogDay day = client.dailyLog(NOV_12, NOV_12).getFirst();

        assertThat(day.activities()).extracting(a -> a.activity().id(), LogDay.Activity::group).containsExactly(
                tuple(1L, null),
                tuple(2L, null));
        // Once from the cache's miss, once more as the type wasn't in it. An activity with no type isn't a reason.
        assertThat(garmin.requests(TYPES)).hasSize(2);
    }

    @Test
    void aTypeGarminAddedIsFoundByAskingForTheListAgain() {
        signedIn();
        // The cached list is older than the type.
        garmin.respond(TYPES, 200, """
                [{"typeId":17,"typeKey":"all","parentTypeId":null},
                 {"typeId":1,"typeKey":"running","parentTypeId":17}]""");
        client.activityTypes();
        // Garmin's list now, queued before the helper's real list so it's the one asked for again.
        garmin.respond(TYPES, 200, """
                [{"typeId":17,"typeKey":"all","parentTypeId":null},{"typeId":1,"typeKey":"running","parentTypeId":17},
                 {"typeId":26,"typeKey":"racket_sports","parentTypeId":17},
                 {"typeId":300,"typeKey":"pickleball","parentTypeId":26}]""");
        nothingButActivities(NOV_12, NOV_12, "[" + String.join(",",
                activity(2, "Pickleball", "pickleball", "2026-11-12T18:00:00Z", 3600),
                activity(1, "Run", "running", "2026-11-12T07:00:00Z", 1800)) + "]");

        LogDay day = client.dailyLog(NOV_12, NOV_12).getFirst();

        assertThat(day.activities()).extracting(LogDay.Activity::group).containsExactly("running", "racket_sports");
        assertThat(garmin.requests(TYPES)).hasSize(2);
        // The new list was cached, so the next call needs no request.
        nothingButActivities(NOV_12, NOV_12, "[" + activity(2, "Pickleball", "pickleball", "2026-11-12T18:00:00Z",
                3600) + "]");
        assertThat(client.dailyLog(NOV_12, NOV_12).getFirst().activities().getFirst().group())
                .isEqualTo("racket_sports");
        assertThat(garmin.requests(TYPES)).hasSize(2);
    }

    @Test
    void theListIsAskedForAgainOnlyOncePerTypePerRun() {
        signedIn();
        String unknown = "[" + activity(1, "Mystery", "underwater_hockey_2030", "2026-11-12T18:00:00Z", 600) + "]";
        garmin.respond(TYPES, 200, fixture("activity-types.json"));
        nothingButActivities(NOV_12, NOV_12, unknown);
        nothingButActivities(NOV_12, NOV_12, unknown);
        nothingButActivities(NOV_12, NOV_12, "[" + String.join(",",
                activity(2, "Another", "sepak_takraw_2031", "2026-11-12T19:00:00Z", 600),
                activity(1, "Mystery", "underwater_hockey_2030", "2026-11-12T18:00:00Z", 600)) + "]");

        client.dailyLog(NOV_12, NOV_12);
        assertThat(garmin.requests(TYPES)).hasSize(2);
        client.dailyLog(NOV_12, NOV_12);
        assertThat(garmin.requests(TYPES)).hasSize(2);
        // A different missing type is worth one more try.
        client.dailyLog(NOV_12, NOV_12);
        assertThat(garmin.requests(TYPES)).hasSize(3);
    }

    @Test
    void a429WhenAskingForTheListAgainIsTriedOnce() {
        signedIn();
        garmin.respond(TYPES, 200, fixture("activity-types.json")).respond(TYPES, 429, "");
        nothingButActivities(NOV_12, NOV_12,
                "[" + activity(1, "Mystery", "underwater_hockey_2030", "2026-11-12T18:00:00Z", 600) + "]");

        assertThat(catchThrowable(() -> client.dailyLog(NOV_12, NOV_12))).isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests(TYPES)).hasSize(2);
    }

    @Test
    void anActivityWithoutAnExactTimeGoesByTheClockWhereItHappenedAndOneWithNoTimeIsLeftOut() {
        signedIn();
        nothingButActivities(NOV_12, NOV_13, """
                [{"activityId":3,"activityType":{"typeKey":"running"},"startTimeLocal":"2026-11-13 00:15:00"},
                 {"activityId":2,"activityType":{"typeKey":"running"}},
                 {"activityId":1,"activityType":{"typeKey":"running"},"startTimeLocal":"2026-11-12 23:45:00"}]""");

        List<LogDay> log = client.dailyLog(NOV_12, NOV_13);

        assertThat(log.get(0).activities()).extracting(a -> a.activity().id()).containsExactly(1L);
        assertThat(log.get(1).activities()).extracting(a -> a.activity().id()).containsExactly(3L);
    }

    @Test
    void noActivitiesMeansTheActivityTypesArentAskedFor() {
        signedIn();
        nothingButActivities(NOV_12, NOV_13, "[]");

        // No watch data at all, and no later day with any, so neither is final.
        assertThat(client.dailyLog(NOV_12, NOV_13)).containsExactly(
                new LogDay(NOV_12, LogDay.State.WATCH_NOT_SYNCED, null, null, null, null, List.of(), null),
                new LogDay(NOV_13, LogDay.State.WATCH_NOT_SYNCED, null, null, null, null, List.of(), null));
        assertThat(garmin.requests(TYPES)).isEmpty();
    }

    @Test
    void todayAndLaterArentFinal() {
        signedIn();
        LocalDate nov21 = NOV_20.plusDays(1);
        // Even with watch data to midnight.
        garmin.respond(STEPS, 200, "{\"data\":[" + String.join(",", synced(NOV_19), synced(NOV_20), synced(nov21))
                + "]}");
        nothingButActivities(NOV_19, nov21, "[]");

        assertThat(client.dailyLog(NOV_19, nov21)).extracting(LogDay::isFinal).containsExactly(true, false, false);
    }

    @Test
    void aPastDayTheWatchHasntSyncedSinceIsntFinal() {
        // As on 22 November 2026: the watch last synced in the morning of the 18th here, and not since.
        signedIn();
        LocalDate nov17 = LocalDate.of(2026, 11, 17);
        LocalDate nov18 = LocalDate.of(2026, 11, 18);
        garmin.respond(STEPS, 200, "{\"data\":[" + synced(nov17) + ","
                + until(nov18, LocalDateTime.of(2026, 11, 18, 8, 42)) + "]}");
        nothingButActivities(nov17, NOV_19, "[]");

        List<LogDay> log = client.dailyLog(nov17, NOV_19);

        assertThat(log).extracting(LogDay::date, LogDay::isFinal, LogDay::watchDataUntil).containsExactly(
                tuple(nov17, true, midnightAfter(nov17)),
                // Stops at the last sync.
                tuple(nov18, false, LocalDateTime.of(2026, 11, 18, 8, 42)),
                // Nothing from the watch at all: yesterday, before the overnight sync.
                tuple(NOV_19, false, null));
    }

    @Test
    void aDayWhoseWatchDataStopsEarlyIsFinalOnceALaterDayHasWatchData() {
        // A flat battery at 20:15 on the 17th, charged and synced on the 18th: the 17th never runs to midnight, but
        // the 18th's data could only have come from a sync after the 17th ended.
        signedIn();
        LocalDate nov17 = LocalDate.of(2026, 11, 17);
        LocalDate nov18 = LocalDate.of(2026, 11, 18);
        garmin.respond(STEPS, 200, "{\"data\":[" + until(nov17, LocalDateTime.of(2026, 11, 17, 20, 15)) + ","
                + until(nov18, LocalDateTime.of(2026, 11, 18, 9, 0)) + "," + synced(NOV_19) + "]}");
        nothingButActivities(nov17, NOV_19, "[]");

        assertThat(client.dailyLog(nov17, NOV_19)).extracting(LogDay::isFinal).containsExactly(true, true, true);
    }

    @Test
    void aLastDayWhoseWatchDataStopsEarlyIsFinalIfTheNextDayHasWatchData() {
        // A flat battery at 18:00 yesterday, charged and synced today. Yesterday alone has no later day in the range,
        // so the next day's steps are asked for too (the owner's choice, 22 November 2026), only to decide.
        signedIn();
        garmin.respond(STEPS, Map.of("startDate", "2026-11-19", "endDate", "2026-11-19"), 200,
                        "{\"data\":[" + until(NOV_19, LocalDateTime.of(2026, 11, 19, 18, 0)) + "]}")
                .respond(STEPS, Map.of("startDate", "2026-11-20", "endDate", "2026-11-20"), 200,
                        "{\"data\":[" + until(NOV_20, LocalDateTime.of(2026, 11, 20, 9, 30)) + "]}");
        nothingElse(NOV_19, NOV_19);

        List<LogDay> log = client.dailyLog(NOV_19, NOV_19);

        assertThat(log).singleElement().satisfies(day -> {
            assertThat(day.state()).isEqualTo(LogDay.State.FINAL);
            assertThat(day.watchDataUntil()).isEqualTo(LocalDateTime.of(2026, 11, 19, 18, 0));
        });
        assertThat(garmin.requests(STEPS)).hasSize(2);
    }

    @Test
    void aLastDayWhoseWatchDataStopsEarlyStaysNotFinalIfTheNextDayHasNone() {
        signedIn();
        garmin.respond(STEPS, Map.of("startDate", "2026-11-19", "endDate", "2026-11-19"), 200,
                        "{\"data\":[" + until(NOV_19, LocalDateTime.of(2026, 11, 19, 18, 0)) + "]}")
                .respond(STEPS, Map.of("startDate", "2026-11-20", "endDate", "2026-11-20"), 200, "{\"data\":[]}");
        nothingElse(NOV_19, NOV_19);

        assertThat(client.dailyLog(NOV_19, NOV_19).getFirst().state()).isEqualTo(LogDay.State.WATCH_NOT_SYNCED);
    }

    @Test
    void theNextDayIsOnlyAskedForWhenTheLastDaysWatchDataStopsEarly() {
        // To midnight: final already. No watch data: not asked (the owner's choice). Today: never final.
        signedIn();
        garmin.respond(STEPS, 200, "{\"data\":[" + synced(NOV_19) + "]}")
                .respond(STEPS, 200, "{\"data\":[]}")
                .respond(STEPS, 200, "{\"data\":[" + until(NOV_20, LocalDateTime.of(2026, 11, 20, 8, 42)) + "]}");
        nothingElse(NOV_19, NOV_19);
        nothingElse(NOV_19, NOV_19);
        nothingElse(NOV_20, NOV_20);

        assertThat(client.dailyLog(NOV_19, NOV_19).getFirst().state()).isEqualTo(LogDay.State.FINAL);
        assertThat(client.dailyLog(NOV_19, NOV_19).getFirst().state()).isEqualTo(LogDay.State.WATCH_NOT_SYNCED);
        assertThat(client.dailyLog(NOV_20, NOV_20).getFirst().state()).isEqualTo(LogDay.State.TODAY);
        assertThat(garmin.requests(STEPS)).hasSize(3);
    }

    @Test
    void aChronologyWithoutStepsIsntWatchData() {
        // Garmin might make a day's chronology when only MyFitnessPal synced (not seen yet, 22 November 2026). So the
        // 19th's calories and chronology to midnight show nothing about the watch: neither day is final.
        signedIn();
        LocalDate nov18 = LocalDate.of(2026, 11, 18);
        garmin.respond(STEPS, 200, "{\"data\":[" + until(nov18, LocalDateTime.of(2026, 11, 18, 8, 42)) + ","
                + """
                {"calendarDate":"2026-11-19","calories":{"consumedValue":3884},
                 "wellnessChronology":{"endTimestampLocal":"2026-11-20T00:00:00.0"}}""" + "]}");
        nothingElse(nov18, NOV_19);

        List<LogDay> log = client.dailyLog(nov18, NOV_19);

        assertThat(log).extracting(LogDay::state, LogDay::watchDataUntil).containsExactly(
                tuple(LogDay.State.WATCH_NOT_SYNCED, LocalDateTime.of(2026, 11, 18, 8, 42)),
                // Its chronology isn't shown as watch data either.
                tuple(LogDay.State.WATCH_NOT_SYNCED, null));
        assertThat(log.get(1).caloriesEaten()).isEqualTo(3884);
        // The 19th has no watch data, so the next day isn't asked for.
        assertThat(garmin.requests(STEPS)).hasSize(1);
    }

    @Test
    void dailyLogRefusesMoreThan28DaysAndSendsNothing() {
        LocalDate tooEarly = NOV_20.minusDays(GarminDataConfig.MAX_LOG_RANGE_DAYS);

        assertThat(catchThrowable(() -> client.dailyLog(tooEarly, NOV_20)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 28 days");
        assertThat(catchThrowable(() -> client.dailyLog(NOV_20, NOV_19)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Put the earlier date first");
        // Not even the profile lookup.
        assertThat(garmin.requests()).isEmpty();
    }

    // Travelling: Garmin's search goes by the date on the clock where the activity happened.

    @Test
    void aRunOnTheEveningBeforeAbroadIsFoundOnTheFirstDay() {
        signedIn();
        // 20:30 on 11 November in New York is 01:30 on the 12th in London.
        garmin.respond(ACTIVITIES, Map.of("startDate", "2026-11-11", "endDate", "2026-11-13", "start", "0",
                "limit", "20"), 200, "[" + activity(1, "Central Park", "running", "2026-11-12T01:30:00Z", 1800,
                ZoneId.of("America/New_York")) + "]");
        nothingButActivities(NOV_12, NOV_12, "[]");

        LogDay day = client.dailyLog(NOV_12, NOV_12).getFirst();

        assertThat(day.activities()).extracting(a -> a.activity().name()).containsExactly("Central Park");
        assertThat(garmin.requests(ACTIVITIES)).singleElement().satisfies(r -> assertThat(r.queryParams())
                .containsEntry("startDate", "2026-11-11").containsEntry("endDate", "2026-11-13"));
    }

    @Test
    void aRunOnTheMorningAfterAbroadIsFoundOnTheLastDay() {
        signedIn();
        // 07:00 on 13 November in Tokyo is 22:00 on the 12th in London.
        garmin.respond(ACTIVITIES, Map.of("startDate", "2026-11-11", "endDate", "2026-11-13", "start", "0",
                "limit", "20"), 200, "[" + activity(1, "Imperial Palace", "running", "2026-11-12T22:00:00Z", 1800,
                ZoneId.of("Asia/Tokyo")) + "]");
        nothingButActivities(NOV_12, NOV_12, "[]");

        LogDay day = client.dailyLog(NOV_12, NOV_12).getFirst();

        assertThat(day.activities()).extracting(a -> a.activity().name()).containsExactly("Imperial Palace");
    }

    @Test
    void activitiesTheWiderSearchFindsOutsideTheRangeAreLeftOut() {
        signedIn();
        // Both at home, the day before and the day after: the search finds them, the log leaves them out.
        nothingButActivities(NOV_12, NOV_12, "[" + String.join(",",
                activity(2, "Day after", "running", "2026-11-13T09:00:00Z", 600),
                activity(1, "Day before", "running", "2026-11-11T09:00:00Z", 600)) + "]");

        assertThat(client.dailyLog(NOV_12, NOV_12)).containsExactly(
                new LogDay(NOV_12, LogDay.State.WATCH_NOT_SYNCED, null, null, null, null, List.of(), null));
        // None in the range, so no need for their groups.
        assertThat(garmin.requests(TYPES)).isEmpty();
    }

    @Test
    void a429IsAskedForOnceAndStopsTheLog() {
        signedIn();
        garmin.respond(STEPS, 429, "");

        assertThat(catchThrowable(() -> client.dailyLog(OCT_24, NOV_20))).isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests(STEPS)).hasSize(1);
        assertThat(garmin.requests(SLEEP)).isEmpty();
        assertThat(garmin.requests(WEIGHT)).isEmpty();
        assertThat(garmin.requests(ACTIVITIES)).isEmpty();
        assertThat(garmin.requests(TYPES)).isEmpty();
    }

    @Test
    void a429OnTheActivityTypesIsAskedForOnce() {
        signedIn();
        garmin.respond(TYPES, 429, "");
        nothingButActivities(NOV_12, NOV_12, "[" + activity(1, "Run", "running", "2026-11-12T07:00:00Z", 600) + "]");

        assertThat(catchThrowable(() -> client.dailyLog(NOV_12, NOV_12))).isInstanceOf(RateLimitedException.class);
        assertThat(garmin.requests(TYPES)).hasSize(1);
    }

    @Test
    void a403OnTheSleepIsAskedForOnce() {
        signedIn();
        garmin.respond(STEPS, 200, "{}").respond(SLEEP, 403, "<html>blocked</html>");

        assertThat(catchThrowable(() -> client.dailyLog(OCT_24, NOV_20)))
                .isInstanceOf(GarminException.class).hasMessageContaining("HTTP 403");
        assertThat(garmin.requests(SLEEP)).hasSize(1);
        assertThat(garmin.requests(WEIGHT)).isEmpty();
    }

    /**
     * Queues empty steps, sleep and weigh-ins for {@code from}–{@code to}, these activities, and the real activity
     * types. To answer with other steps or types, queue them first: the first matching reply is the one taken.
     */
    private void nothingButActivities(LocalDate from, LocalDate to, String activities) {
        garmin.respond(STEPS, 200, "{\"data\":[]}")
                .respond("/sleep-service/stats/sleep/daily/" + from + "/" + to, 200, "{\"individualStats\":[]}")
                .respond("/weight-service/weight/range/" + from + "/" + to, 200, "{\"dailyWeightSummaries\":[]}")
                .respond(ACTIVITIES, 200, activities)
                .respond(TYPES, 200, fixture("activity-types.json"));
    }

    /** A made-up activity in the search's shape, with its start in UTC and the clock time in London. */
    private static String activity(long id, String name, String type, String startUtc, double seconds) {
        return activity(id, name, type, startUtc, seconds, LONDON);
    }

    /** The same, recorded where the clock time is {@code where}'s. */
    private static String activity(long id, String name, String type, String startUtc, double seconds,
            ZoneId where) {
        Instant start = Instant.parse(startUtc);
        String gmt = start.toString().replace('T', ' ').replace("Z", "");
        String local = start.atZone(where).toLocalDateTime().toString().replace('T', ' ');
        return """
                {"activityId":%d,"activityName":"%s","activityType":{"typeKey":"%s"},
                 "startTimeLocal":"%s","startTimeGMT":"%s","duration":%s}""".formatted(id, name, type, local, gmt,
                seconds);
    }

    /** Queues empty sleep, weigh-ins and activities for {@code from}–{@code to}, but no steps. */
    private void nothingElse(LocalDate from, LocalDate to) {
        garmin.respond("/sleep-service/stats/sleep/daily/" + from + "/" + to, 200, "{\"individualStats\":[]}")
                .respond("/weight-service/weight/range/" + from + "/" + to, 200, "{\"dailyWeightSummaries\":[]}")
                .respond(ACTIVITIES, 200, "[]");
    }

    /** Midnight at the end of {@code day}: where a synced day's watch data ends. */
    private static LocalDateTime midnightAfter(LocalDate day) {
        return day.plusDays(1).atStartOfDay();
    }

    /** A day in the steps range reply with 5,000 steps and watch data to midnight. */
    private static String synced(LocalDate day) {
        return until(day, midnightAfter(day));
    }

    /** A day in the steps range reply with 5,000 steps and watch data to {@code end} (local). */
    private static String until(LocalDate day, LocalDateTime end) {
        return """
                {"calendarDate":"%s","movement":{"steps":{"value":5000}},
                 "wellnessChronology":{"startTimestampLocal":"%sT00:00:00.0","endTimestampLocal":"%s"}}""".formatted(
                day, day, end.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.0")));
    }

    /** Queues the profile and its time zone (Europe/London) once. */
    private void signedIn() {
        garmin.respond(PROFILE_PATH, 200, fixture("social-profile.json"))
                .respond(PERSONAL, 200, fixture("personal-information.json"));
    }

    private static String fixture(String name) {
        return Fixtures.read(name);
    }
}
