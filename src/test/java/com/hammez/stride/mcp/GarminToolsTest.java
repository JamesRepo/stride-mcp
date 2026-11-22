package com.hammez.stride.mcp;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITIES;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITY_TYPES;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITY;
import static com.hammez.stride.mcp.ToolFixtures.DAY_WITH_TWO_WEIGH_INS;
import static com.hammez.stride.mcp.ToolFixtures.LAST_NIGHT;
import static com.hammez.stride.mcp.ToolFixtures.LAST_SEVEN_DAYS;
import static com.hammez.stride.mcp.ToolFixtures.LOG_ACTIVITIES;
import static com.hammez.stride.mcp.ToolFixtures.LOG_FROM_THE_CAPTURE;
import static com.hammez.stride.mcp.ToolFixtures.LOG_NOW;
import static com.hammez.stride.mcp.ToolFixtures.LOG_FILE;
import static com.hammez.stride.mcp.ToolFixtures.NOW;
import static com.hammez.stride.mcp.ToolFixtures.PERSONAL;
import static com.hammez.stride.mcp.ToolFixtures.SLEEP;
import static com.hammez.stride.mcp.ToolFixtures.SLEEP_RANGE_PATH;
import static com.hammez.stride.mcp.ToolFixtures.SPLITS;
import static com.hammez.stride.mcp.ToolFixtures.STAIR_STEPPER;
import static com.hammez.stride.mcp.ToolFixtures.STEPS_RANGE;
import static com.hammez.stride.mcp.ToolFixtures.SUMMARY;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_PATH;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_RANGE;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_RANGE_PATH;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_SINCE_OCTOBER;
import static com.hammez.stride.mcp.ToolFixtures.compact;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

import com.hammez.stride.garmin.Fixtures;
import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.auth.FakeGarmin;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools, called through Spring AI's own tool callbacks (as the server calls them), on a real GarminClient and
 * cache talking to a fake Garmin, with replies from the fixtures. As in DataCommandsTest, the profile's time zone
 * is Europe/London, where it is already Sat 24 October (00:30), while in UTC it is still the 23rd. So "today" is
 * the 24th. The daily log's tests run on the evening of its capture instead ({@link #onTheEveningOfTheCapture()}).
 * <p>
 * What the server adds on top (the SDK checking inputs against the schema, the JSON-RPC around it) is in
 * ServeModeTest.
 * <p>
 * Every test also checks that no token shows up in any reply or log line.
 */
class GarminToolsTest {

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private final JsonMapper json = JsonMapper.builder().build();
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<String> replies = new ArrayList<>();
    private final Logger toolsLog = (Logger) LoggerFactory.getLogger(GarminTools.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level toolsLogLevel;
    private List<SyncToolSpecification> tools;

    @BeforeEach
    void captureTheLog() {
        // INFO, as in serve mode: the one line per call.
        toolsLogLevel = toolsLog.getLevel();
        toolsLog.setLevel(Level.INFO);
        logged.start();
        toolsLog.addAppender(logged);
    }

    @AfterEach
    void noTokenEverShows() {
        garmin.close();
        toolsLog.detachAppender(logged);
        toolsLog.setLevel(toolsLogLevel);
        assertThat(String.join("\n", replies)).doesNotContain(FakeGarmin.ACCESS_TOKEN)
                .doesNotContain(FakeGarmin.REFRESH_TOKEN);
        assertThat(log()).doesNotContain(FakeGarmin.ACCESS_TOKEN).doesNotContain(FakeGarmin.REFRESH_TOKEN);
    }

    // What the AI sees in tools/list

    @Test
    void sixReadOnlyToolsWithThePlansDescriptions() {
        assertThat(tools()).extracting(spec -> spec.tool().name())
                .containsExactlyInAnyOrder("get_activities", "get_activity", "get_daily_summary", "get_sleep",
                        "get_weight", "get_daily_log");
        assertThat(tool("get_activities").description()).isEqualTo(GarminTools.GET_ACTIVITIES);
        assertThat(tool("get_activity").description()).isEqualTo(GarminTools.GET_ACTIVITY);
        assertThat(tool("get_daily_summary").description()).isEqualTo(GarminTools.GET_DAILY_SUMMARY);
        assertThat(tool("get_sleep").description()).isEqualTo(GarminTools.GET_SLEEP);
        assertThat(tool("get_weight").description()).isEqualTo(GarminTools.GET_WEIGHT);
        assertThat(tool("get_daily_log").description()).isEqualTo(GarminTools.GET_DAILY_LOG);

        for (SyncToolSpecification spec : tools()) {
            Tool tool = spec.tool();
            assertThat(tool.annotations().readOnlyHint()).as(tool.name()).isTrue();
            assertThat(tool.annotations().destructiveHint()).as(tool.name()).isFalse();
            assertThat(tool.outputSchema()).as(tool.name()).isNull();
            assertThat(property(tool, "refresh")).as(tool.name())
                    .isEqualTo(Map.of("type", "boolean", "description", GarminTools.REFRESH));
        }
    }

    @Test
    void theNumbersInTheDescriptionsMatchTheRulesAndTheCache() {
        // Built from GarminDataConfig: check the joins read right.
        assertThat(GarminTools.GET_ACTIVITIES).endsWith("`from` and `to` are dates like 2026-10-22, both included, "
                + "covering at most 90 days (for example 2026-07-26 to 2026-10-23). Leave both out for the last 7 "
                + "days. The reply also gives today's date in the owner's time zone.");
        // The example range is the longest allowed.
        var example = Pattern.compile("for example (\\S+) to (\\S+)\\)").matcher(
                GarminTools.GET_ACTIVITIES);
        assertThat(example.find()).isTrue();
        assertThat(ChronoUnit.DAYS.between(LocalDate.parse(example.group(1)),
                LocalDate.parse(example.group(2))) + 1).isEqualTo(GarminDataConfig.MAX_RANGE_DAYS);
        // What refresh says about the cache is what the cache does.
        var lifetimes = GarminDataConfig.defaults(garmin.config()).cacheLifetimes();
        assertThat(GarminTools.REFRESH).contains(
                lifetimes.recentActivities().toMinutes() + " to " + lifetimes.recentDay().toMinutes() + " minutes",
                "an activity's details for " + lifetimes.activity().toDays() + " days");
        assertThat(GarminTools.GET_WEIGHT).endsWith("`from` and `to` are dates like 2026-09-01, both included, in "
                + "the owner's time zone, covering at most 367 days, so a year to the same date fits. Leave both out "
                + "for the last 30 days, today included. A range ending before yesterday is cached for a day, or an "
                + "hour if it had no weigh-ins. The reply also gives today's date in the owner's time zone.");
        assertThat(lifetimes.emptyOlderDay()).isEqualTo(Duration.ofHours(1));
        assertThat(lifetimes.olderRange()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void theDailyLogsDescriptionMatchesTheRulesAndTheCache() {
        // Built from GarminDataConfig: check the joins read right.
        assertThat(GarminTools.GET_DAILY_LOG).endsWith("`from` and `to` are dates like 2026-11-08, both included, "
                + "in the owner's time zone, covering at most 28 days and none after today. For more, call again "
                + "for each 28 days. Leave both out for yesterday. Only `from`: from there to yesterday, or just "
                + "today if `from` is today. Only `to`: that one day. A range ending before yesterday is cached for "
                + "up to a day once the watch has synced past it, otherwise its sleep, steps and activities for up "
                + "to 15 minutes. The reply also gives today's date in the owner's time zone.");
        // "Yesterday" and "that one day" are one day.
        assertThat(GarminDataConfig.DEFAULT_LOG_RANGE_DAYS).isEqualTo(1);
        // What it says the AI must know.
        assertThat(GarminTools.GET_DAILY_LOG).contains(
                "caloriesEaten is the food logged that day in kcal (MyFitnessPal's daily total, as synced to "
                        + "Garmin). It is never calories burned.",
                "final: false means the day may still change, so don't log it yet: it's today, or a past day the "
                        + "watch hasn't synced since.",
                "watchDataUntil: where Garmin's watch data for it stops (local, like 08:42, or none). Then ask the "
                        + "owner to sync their watch, and call again with refresh.",
                "the night that ended on the morning of that date",
                "group is Garmin's top-level type",
                "minutes (the whole duration, not the moving time)",
                // The JSON's own names: sleep.hours, weight.kg and weight.time (LogReply).
                "score (0 to 100) and hours (time asleep)",
                "weight: kg (the day's first weight)",
                "time (local, like 07:37) of the weigh-in that gave the weight, or of the scale reading if no "
                        + "weigh-in had a weight");
        // Every field of a day is named, as the JSON has it.
        for (Class<?> part : List.of(LogReply.Day.class, LogReply.Sleep.class, LogReply.Activity.class,
                LogReply.Weight.class)) {
            for (var field : part.getRecordComponents()) {
                String name = field.getName().equals("isFinal") ? "final" : field.getName();
                assertThat(GarminTools.GET_DAILY_LOG).as(part.getSimpleName()).containsPattern("\\b" + name + "\\b");
            }
        }
        // "Up to a day": the steps, sleep and weigh-ins over a range ending before yesterday, and the activity
        // search (a day wider, so it can end yesterday, and then it's less). Less again if Garmin had nothing.
        // "Otherwise up to 15 minutes": an unsynced range's steps and sleep as recent days, activities as recent ones.
        var lifetimes = GarminDataConfig.defaults(garmin.config()).cacheLifetimes();
        assertThat(lifetimes.olderRange()).isEqualTo(Duration.ofDays(1));
        assertThat(lifetimes.olderActivities()).isEqualTo(Duration.ofDays(1));
        assertThat(lifetimes.emptyOlderDay()).isEqualTo(Duration.ofHours(1));
        assertThat(lifetimes.recentDay()).isEqualTo(Duration.ofMinutes(15));
        assertThat(lifetimes.recentActivities()).isLessThanOrEqualTo(Duration.ofMinutes(15));
        // The examples in it are real types and groups in Garmin's list.
        assertThat(GarminTools.GET_DAILY_LOG).contains("running for running, trail_running and treadmill_running, "
                + "cycling for indoor_cycling, fitness_equipment for strength_training");
    }

    @Test
    void theInputsTheAiSees() {
        assertThat(tool("get_activities").inputSchema().get("properties")).asInstanceOf(
                InstanceOfAssertFactories.MAP).containsOnlyKeys("from", "to", "type", "refresh");
        assertThat(property(tool("get_activities"), "from")).containsEntry("type", "string");
        assertThat(tool("get_activities").inputSchema().get("required")).isEqualTo(List.of());

        assertThat(tool("get_weight").inputSchema().get("properties")).asInstanceOf(
                InstanceOfAssertFactories.MAP).containsOnlyKeys("from", "to", "refresh");
        assertThat(property(tool("get_weight"), "from")).containsEntry("type", "string");
        assertThat(property(tool("get_weight"), "to")).containsEntry("type", "string");
        assertThat(tool("get_weight").inputSchema().get("required")).isEqualTo(List.of());

        assertThat(tool("get_daily_log").inputSchema().get("properties")).asInstanceOf(
                InstanceOfAssertFactories.MAP).containsOnlyKeys("from", "to", "refresh");
        assertThat(property(tool("get_daily_log"), "from")).containsEntry("type", "string");
        assertThat(property(tool("get_daily_log"), "to")).containsEntry("type", "string");
        assertThat(tool("get_daily_log").inputSchema().get("required")).isEqualTo(List.of());

        assertThat(property(tool("get_activity"), "id")).containsEntry("type", "integer");
        assertThat(tool("get_activity").inputSchema().get("required")).isEqualTo(List.of("id"));

        for (String name : List.of("get_daily_summary", "get_sleep")) {
            assertThat(property(tool(name), "date")).as(name).containsEntry("type", "string");
            assertThat(tool(name).inputSchema().get("required")).as(name).isEqualTo(List.of());
        }
    }

    // get_activities

    @Test
    void activitiesFromTheRealListForTheLastSevenDays() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, Fixtures.read("activities.json"));

        String reply = ok("get_activities", Map.of());

        // In UTC it would be the 17th to the 23rd.
        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams())
                .isEqualTo(Map.of("startDate", "2026-10-18", "endDate", "2026-10-24", "start", "0", "limit", "20"));
        // The fake answers with all 8, whatever the dates. Garmin's 0s (distance, speed, moving time) are left out.
        assertThat(reply).isEqualTo(compact(LAST_SEVEN_DAYS));
    }

    @Test
    void activitiesPassTheDatesAndTypeOnAndNoneIsNotAnError() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        String reply = ok("get_activities", Map.of("from", "2026-07-27", "to", "2026-10-24", "type", "running"));

        // 90 days, both ends included, is allowed.
        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams()).isEqualTo(Map.of("startDate", "2026-07-27",
                "endDate", "2026-10-24", "start", "0", "limit", "20", "activityType", "running"));
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","from":"2026-07-27","to":"2026-10-24",
                 "type":"running","count":0,"activities":[]}"""));
    }

    @Test
    void onlyToMeansTheSevenDaysEndingThere() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        ok("get_activities", Map.of("to", "2026-09-30"));

        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams())
                .containsEntry("startDate", "2026-09-24").containsEntry("endDate", "2026-09-30");
    }

    @Test
    void onlyFromMeansFromThereToToday() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        ok("get_activities", Map.of("from", "2026-10-01"));

        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams())
                .containsEntry("startDate", "2026-10-01").containsEntry("endDate", "2026-10-24");
    }

    @Test
    void blankInputsAreTakenAsLeftOut() {
        // Some AI apps send "" for "no value".
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]");

        String reply = ok("get_activities", Map.of("from", "", "to", " ", "type", ""));

        assertThat(garmin.requests(ACTIVITIES).getFirst().queryParams())
                .isEqualTo(Map.of("startDate", "2026-10-18", "endDate", "2026-10-24", "start", "0", "limit", "20"));
        assertThat(reply).doesNotContain("\"type\"");
    }

    @Test
    void anActivityWithOnlyAnIdAndTypeHasNoOtherFields() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[{\"activityId\":7,\"activityType\":{\"typeKey\":\"running\"}}]");

        String reply = ok("get_activities", Map.of());

        assertThat(json.readTree(reply).path("activities").get(0)).isEqualTo(json.readTree("""
                {"id":7,"type":"running"}"""));
        assertThat(reply).doesNotContain("null");
    }

    // get_activity

    @Test
    void activityFromTheRealReply() {
        signedIn();
        garmin.respond(ACTIVITY, 200, Fixtures.read("activity.json"))
                .respond(SPLITS, 200, Fixtures.read("activity-splits.json"));

        String reply = ok("get_activity", Map.of("id", 24816092240L));

        // A stair stepper session: Garmin's 0 distance, speed and moving time are left out, for the lap too.
        assertThat(reply).isEqualTo(compact(STAIR_STEPPER));
    }

    @Test
    void aRunHasPaceCadencePowerAndEachLap() {
        // Made up, as in DataCommandsTest: the sample was a stair stepper session.
        signedIn();
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

        String reply = ok("get_activity", Map.of("id", 24816092240L));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","id":24816092240,"name":"Hill reps","type":"running",
                 "start":"2026-10-22T07:06","distanceKm":5.05,"duration":"36:01","durationSeconds":2161,
                 "movingDuration":"35:51","movingDurationSeconds":2151,"pace":"7:08 /km","fastestPace":"4:30 /km",
                 "elevationGainMetres":18,"averageHeartRateBpm":143,"maxHeartRateBpm":169,
                 "averageCadenceStepsPerMinute":163,"averagePowerWatts":302,"kilocalories":1448,
                 "aerobicTrainingEffect":3.2,"anaerobicTrainingEffect":0.5,"trainingLoad":85,
                 "laps":[
                  {"number":1,"distanceKm":1.0,"duration":"6:58","durationSeconds":418,"pace":"6:58 /km",
                   "averageHeartRateBpm":139,"maxHeartRateBpm":151,"elevationGainMetres":4},
                  {"number":2,"distanceKm":4.05,"duration":"1:02:23","durationSeconds":3743,"pace":"15:23 /km"}]}"""));
    }

    // get_daily_summary

    @Test
    void dailySummaryWithTheDaysWeightAndBodyComposition() {
        signedIn();
        garmin.respond(SUMMARY, Map.of("calendarDate", "2026-10-23"), 200, Fixtures.read("summary-today.json"))
                .respond(WEIGHT_PATH + "2026-10-23", Map.of("includeAll", "true"), 200, Fixtures.TWO_WEIGH_INS);

        String reply = ok("get_daily_summary", Map.of("date", "2026-10-23"));

        // Intensity minutes of 0 are real values: only activities' 0s mean "doesn't apply". No sleep: get_sleep has it.
        assertThat(reply).isEqualTo(compact(DAY_WITH_TWO_WEIGH_INS));
        assertThat(garmin.requests(SLEEP)).isEmpty();
    }

    @Test
    void dailySummaryDefaultsToTodayAndNoDataIsNotAnError() {
        signedIn();
        garmin.respond(SUMMARY, Map.of("calendarDate", "2026-10-24"), 200, "{\"totalSteps\":null}")
                .respond(WEIGHT_PATH + "2026-10-24", 200, Fixtures.read("weight-none.json"));

        String reply = ok("get_daily_summary", Map.of());

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-24","recorded":false}"""));
    }

    @Test
    void aWeightTypedInAfterTheScaleDoesntCount() {
        // The day's weight is its first weigh-in (the owner's choice, 20 November 2026).
        signedIn();
        garmin.respond(SUMMARY, 200, "{}").respond(WEIGHT_PATH + "2026-10-23", 200, """
                {"dateWeightList":[
                 {"weight":75900.0,"bmi":23.4,"timestampGMT":1792785600000,"sourceType":"USER_SETTING"},
                 {"weight":75400.0,"bmi":23.3,"bodyFat":18.6,"timestampGMT":1792737900000,
                  "sourceType":"INDEX_SCALE"}]}""");

        String reply = ok("get_daily_summary", Map.of("date", "2026-10-23"));

        // Recorded, though the day's totals are empty: there was a weigh-in.
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,
                 "weight":{"kilograms":75.4,"weighIns":2,"bmi":23.3,"bodyFatPercent":18.6}}"""));
    }

    @Test
    void aWeightTypedInBeforeTheScaleKeepsTheScalesBodyComposition() {
        // The weight is the first weigh-in, the body composition the first scale reading. The typed-in weight's
        // BMI (Garmin works one out, as in the real capture) doesn't make it the body composition.
        signedIn();
        garmin.respond(SUMMARY, 200, "{}").respond(WEIGHT_PATH + "2026-10-23", 200, """
                {"dateWeightList":[
                 {"weight":75400.0,"bmi":23.3,"bodyFat":18.6,"timestampGMT":1792737900000,
                  "sourceType":"INDEX_SCALE"},
                 {"weight":75900.0,"bmi":23.4,"timestampGMT":1792737600000,"sourceType":"USER_SETTING"}]}""");

        String reply = ok("get_daily_summary", Map.of("date", "2026-10-23"));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,
                 "weight":{"kilograms":75.9,"weighIns":2,"bodyCompositionFromAnotherWeighIn":true,"bmi":23.3,
                   "bodyFatPercent":18.6}}"""));
    }

    @Test
    void dailySummaryKeepsTheTotalsWhenTheWeighInsCantBeRead() {
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"totalSteps\":3532}")
                .respond(WEIGHT_PATH + "2026-10-23", 200, "{\"dateWeightList\":\"none\"}");

        String reply = ok("get_daily_summary", Map.of("date", "2026-10-23"));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,"steps":3532,
                 "weightLeftOut":"Garmin's reply for the weigh-ins for 2026-10-23 wasn't what stride-mcp expected. \
                Garmin may have changed it. Check for an update to stride-mcp."}"""));
    }

    // get_sleep

    @Test
    void sleepFromTheRealReply() {
        signedIn();
        garmin.respond(SLEEP, Map.of("date", "2026-10-23", "nonSleepBufferMinutes", "60"), 200,
                Fixtures.read("sleep-last-night.json"));

        String reply = ok("get_sleep", Map.of("date", "2026-10-23"));

        // Times in the profile's time zone (British Summer Time), not the computer's (UTC here).
        assertThat(reply).isEqualTo(compact(LAST_NIGHT));
    }

    @Test
    void sleepDefaultsToLastNightAndNoSleepIsNotAnError() {
        signedIn();
        garmin.respond(SLEEP, Map.of("date", "2026-10-24", "nonSleepBufferMinutes", "60"), 200,
                Fixtures.read("sleep-none.json"));

        String reply = ok("get_sleep", Map.of());

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-24","recorded":false}"""));
    }

    // get_weight

    @Test
    void weightFromTheRealReply() {
        signedIn();
        garmin.respond(WEIGHT_RANGE, Map.of("includeAll", "true"), 200, Fixtures.read("weight-range.json"));

        String reply = ok("get_weight", Map.of("from", "2026-10-22", "to", "2026-11-20"));

        assertThat(reply).isEqualTo(compact(WEIGHT_SINCE_OCTOBER));
        assertThat(garmin.requests(WEIGHT_RANGE)).hasSize(1);
    }

    @Test
    void weightDefaultsToTheLastThirtyDaysAndNoWeighInsIsNotAnError() {
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-09-25/2026-10-24", 200, "{\"dailyWeightSummaries\":[]}");

        String reply = ok("get_weight", Map.of());

        // 30 days, today (in the profile's time zone) included.
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","from":"2026-09-25","to":"2026-10-24",
                 "summary":{"daysWithWeighIns":0},"days":[]}"""));
    }

    @Test
    void weightWithOnlyToOrOnlyFromFollowsGetActivities() {
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-09-01/2026-09-30", 200, "{}")
                .respond(WEIGHT_RANGE_PATH + "2026-09-01/2026-10-24", 200, "{}");

        // Only to: the 30 days ending there. Only from: from there to today.
        assertThat(json.readTree(ok("get_weight", Map.of("to", "2026-09-30"))).path("from").asString())
                .isEqualTo("2026-09-01");
        assertThat(json.readTree(ok("get_weight", Map.of("from", "2026-09-01"))).path("to").asString())
                .isEqualTo("2026-10-24");
        assertThat(garmin.requests(WEIGHT_RANGE_PATH + "2026-09-01/2026-09-30")).hasSize(1);
        assertThat(garmin.requests(WEIGHT_RANGE_PATH + "2026-09-01/2026-10-24")).hasSize(1);
    }

    @Test
    void weightForAYearToTheSameDateAcrossTwentyNinthFebruary() {
        // 2027-03-01 to 2028-03-01 is 367 days with both ends counted: the most allowed.
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2027-03-01/2028-03-01", 200, "{}");

        ok("get_weight", Map.of("from", "2027-03-01", "to", "2028-03-01"));

        assertThat(garmin.requests(WEIGHT_RANGE_PATH + "2027-03-01/2028-03-01")).hasSize(1);
    }

    @Test
    void aDayWithTwoWeighInsTakesTheFirstForTheWeightAndTheBodyComposition() {
        // Made up, with the real reply's field names. Garmin lists a day's weigh-ins newest first.
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-10-01/2026-10-24", 200, """
                {"dailyWeightSummaries":[
                 {"summaryDate":"2026-10-23","allWeightMetrics":[
                  {"weight":75000.0,"bodyFat":17.9,"muscleMass":33200.0,"visceralFat":6,"metabolicAge":34,
                   "timestampGMT":1792760400000,"sourceType":"INDEX_SCALE"},
                  {"weight":75400.0,"bodyFat":18.6,"muscleMass":33100.0,"visceralFat":7,"metabolicAge":35,
                   "timestampGMT":1792737900000,"sourceType":"INDEX_SCALE"}]},
                 {"summaryDate":"2026-10-20","allWeightMetrics":[
                  {"weight":76000.0,"bmi":23.4,"bodyFat":18.9,"timestampGMT":1792479900000,
                   "sourceType":"INDEX_SCALE"},
                  {"weight":76200.0,"bmi":23.5,"timestampGMT":1792479600000,"sourceType":"USER_SETTING"}]},
                 {"summaryDate":"2026-10-02","allWeightMetrics":[
                  {"weight":75400.0,"bmi":23.3,"timestampGMT":1790913600000,"sourceType":"USER_SETTING"}]}]}""");

        String reply = ok("get_weight", Map.of("from", "2026-10-01"));

        // 23 October: the 07:45 scale reading, for both, not the 14:00 one. 20 October: a weight typed in at 08:00,
        // then the scale's body composition at 08:05. The lowest is a tie, so the earlier day is given.
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","from":"2026-10-01","to":"2026-10-24",
                 "summary":{"daysWithWeighIns":3,"first":{"date":"2026-10-02","weightKg":75.4},
                   "last":{"date":"2026-10-23","weightKg":75.4},"changeKg":0.0,
                   "lowest":{"date":"2026-10-02","weightKg":75.4},"highest":{"date":"2026-10-20","weightKg":76.2}},
                 "days":[
                  {"date":"2026-10-02","time":"05:00","weightKg":75.4,"bmi":23.3,"weighIns":1},
                  {"date":"2026-10-20","time":"08:00","weightKg":76.2,"bodyFatPercent":18.9,"bmi":23.4,"weighIns":2,
                   "bodyCompositionFromAnotherWeighIn":true},
                  {"date":"2026-10-23","time":"07:45","weightKg":75.4,"bodyFatPercent":18.6,"muscleMassKg":33.1,
                   "visceralFat":7,"metabolicAge":35,"weighIns":2}]}"""));
    }

    @Test
    void aWeightTypedInBeforeTheScaleTakesNoneOfTheScalesBodyComposition() {
        // As in the real capture, the typed-in weight has a BMI Garmin worked out. 07:30, then the scale at 07:35.
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-10-20/2026-10-24", 200, """
                {"dailyWeightSummaries":[
                 {"summaryDate":"2026-10-20","allWeightMetrics":[
                  {"weight":75900.0,"bmi":23.3,"bodyFat":18.2,"bodyWater":55.4,"muscleMass":33100.0,
                   "boneMass":3200.0,"timestampGMT":1792478100000,"sourceType":"INDEX_SCALE"},
                  {"weight":76200.0,"bmi":23.5,"bodyFat":null,"bodyWater":null,"muscleMass":null,"boneMass":null,
                   "timestampGMT":1792477800000,"sourceType":"USER_SETTING"}]}]}""");

        String reply = ok("get_weight", Map.of("from", "2026-10-20"));

        // The weight and its time from the typed-in weight; everything else, BMI included, from the scale.
        assertThat(json.readTree(reply).path("days")).isEqualTo(json.readTree("""
                [{"date":"2026-10-20","time":"07:30","weightKg":76.2,"bodyFatPercent":18.2,"bodyWaterPercent":55.4,
                  "muscleMassKg":33.1,"boneMassKg":3.2,"bmi":23.3,"weighIns":2,
                  "bodyCompositionFromAnotherWeighIn":true}]"""));
    }

    @Test
    void aTieIsBetweenTheWeightsAsShownAndGivesTheEarlierDay() {
        // 75.04 and 74.96 both show as 75.0: the lowest is the earlier day, not the lighter one. The same for the
        // highest, with 76.04 and 75.96.
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-10-01/2026-10-31", 200, """
                {"dailyWeightSummaries":[
                 {"summaryDate":"2026-10-30","allWeightMetrics":[{"weight":74960.0,"timestampGMT":1793347200000}]},
                 {"summaryDate":"2026-10-23","allWeightMetrics":[{"weight":75040.0,"timestampGMT":1792737900000}]},
                 {"summaryDate":"2026-10-12","allWeightMetrics":[{"weight":76040.0,"timestampGMT":1791788400000}]},
                 {"summaryDate":"2026-10-05","allWeightMetrics":[{"weight":75960.0,"timestampGMT":1791183600000}]}]}""");

        String reply = ok("get_weight", Map.of("from", "2026-10-01", "to", "2026-10-31"));

        assertThat(json.readTree(reply).path("summary")).isEqualTo(json.readTree("""
                {"daysWithWeighIns":4,"first":{"date":"2026-10-05","weightKg":76.0},
                 "last":{"date":"2026-10-30","weightKg":75.0},"changeKg":-1.0,
                 "lowest":{"date":"2026-10-23","weightKg":75.0},"highest":{"date":"2026-10-05","weightKg":76.0}}"""));
    }

    @Test
    void theChangeIsWorkedOutFromTheWeightsAsShown() {
        // 76.04 shows as 76.0 and 75.66 as 75.7: the change shown is -0.3, not the -0.38 between the two.
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-10-01/2026-10-24", 200, """
                {"dailyWeightSummaries":[
                 {"summaryDate":"2026-10-23","allWeightMetrics":[{"weight":75660.0,"timestampGMT":1792737900000}]},
                 {"summaryDate":"2026-10-02","allWeightMetrics":[{"weight":76040.0,"timestampGMT":1790913600000}]}]}""");

        String reply = ok("get_weight", Map.of("from", "2026-10-01"));

        assertThat(json.readTree(reply).path("summary")).isEqualTo(json.readTree("""
                {"daysWithWeighIns":2,"first":{"date":"2026-10-02","weightKg":76.0},
                 "last":{"date":"2026-10-23","weightKg":75.7},"changeKg":-0.3,
                 "lowest":{"date":"2026-10-23","weightKg":75.7},"highest":{"date":"2026-10-02","weightKg":76.0}}"""));
        assertThat(reply).contains("\"weightKg\":76.0", "\"weightKg\":75.7");
    }

    @Test
    void aDayWithOnlyBodyCompositionIsListedButNotInTheSummary() {
        signedIn();
        garmin.respond(WEIGHT_RANGE_PATH + "2026-10-01/2026-10-24", 200, """
                {"dailyWeightSummaries":[
                 {"summaryDate":"2026-10-23","allWeightMetrics":[{"bodyFat":18.6}]}]}""");

        String reply = ok("get_weight", Map.of("from", "2026-10-01"));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","from":"2026-10-01","to":"2026-10-24",
                 "summary":{"daysWithWeighIns":1},
                 "days":[{"date":"2026-10-23","bodyFatPercent":18.6,"weighIns":1}]}"""));
    }

    @Test
    void weightsBadRangesAreRefusedWithNothingSent() {
        assertThat(error("get_weight", Map.of("from", "2026-10-23", "to", "2026-10-22")))
                .isEqualTo("`from` (2026-10-23) is after `to` (2026-10-22). Put the earlier date first.");
        assertThat(error("get_weight", Map.of("from", "2027-03-01", "to", "2028-03-02")))
                .isEqualTo("The range can be at most 367 days. 2027-03-01 to 2028-03-02 is 368 days, both "
                        + "included.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void weightsFromAfterTodayOrTooLongAgoWithNoToNeedsOnlyTheProfile() {
        signedIn();

        assertThat(error("get_weight", Map.of("from", "2026-10-25")))
                .isEqualTo("`from` (2026-10-25) is after today (2026-10-24).");
        assertThat(error("get_weight", Map.of("from", "2025-10-22")))
                .isEqualTo("The range can be at most 367 days. 2025-10-22 to 2026-10-24 is 368 days, both "
                        + "included.");

        assertThat(garmin.requests()).noneMatch(request -> request.path().startsWith(WEIGHT_RANGE_PATH));
    }

    @Test
    void weightRateLimitedOnceWithNoRetry() {
        signedIn();
        garmin.respond(WEIGHT_RANGE, 429, "");

        assertThat(error("get_weight", Map.of("from", "2026-10-22", "to", "2026-11-20")))
                .isEqualTo("Garmin is rate limiting. Wait a while (an hour or more) before trying again.");
        assertThat(garmin.requests(WEIGHT_RANGE)).hasSize(1);
        assertThat(log()).isEqualTo("WARN get_weight failed in N ms: Garmin is rate limiting. Wait a while "
                + "(an hour or more) before trying again.");
    }

    @Test
    void weightForbiddenOnceWithNoRetryAndGarminsReplyLeftOut() {
        signedIn();
        garmin.respond(WEIGHT_RANGE, 403, "<html>blocked</html>");

        String told = error("get_weight", Map.of("from", "2026-10-22", "to", "2026-11-20"));

        assertThat(told).isEqualTo("Garmin refused the request during the request to " + WEIGHT_RANGE
                + " (HTTP 403). It may be blocking this connection. Wait a while before trying again.");
        assertThat(garmin.requests(WEIGHT_RANGE)).hasSize(1);
        assertThat(log()).doesNotContain("blocked");
    }

    // get_daily_log

    @Test
    void dailyLogFromTheRealReplies() {
        onTheEveningOfTheCapture();
        signedIn();
        garmin.respond(STEPS_RANGE, Map.of("startDate", "2026-10-24", "endDate", "2026-11-20"), 200,
                        Fixtures.read("summary-range.json"))
                .respond(SLEEP_RANGE_PATH + "2026-10-24/2026-11-20", 200, Fixtures.read("sleep-range.json"))
                .respond(WEIGHT_RANGE_PATH + "2026-10-24/2026-11-20", Map.of("includeAll", "true"), 200,
                        Fixtures.read("weight-range.json"))
                // A day wider at each end, for an activity recorded abroad.
                .respond(ACTIVITIES, Map.of("startDate", "2026-10-23", "endDate", "2026-11-21", "start", "0",
                        "limit", "20"), 200, LOG_ACTIVITIES)
                .respond(ACTIVITY_TYPES, 200, Fixtures.read("activity-types.json"));

        String reply = ok("get_daily_log", Map.of("from", "2026-10-24", "to", "2026-11-20"));

        assertThat(reply).isEqualTo(compact(LOG_FROM_THE_CAPTURE));
        // About five requests for 28 days: one of each kind.
        for (String path : List.of(STEPS_RANGE, SLEEP_RANGE_PATH + "2026-10-24/2026-11-20",
                WEIGHT_RANGE_PATH + "2026-10-24/2026-11-20", ACTIVITIES, ACTIVITY_TYPES)) {
            assertThat(garmin.requests(path)).as(path).hasSize(1);
        }
        assertThat(log()).isEqualTo("INFO get_daily_log worked in N ms");
    }

    @Test
    void dailyLogLeavesOutWhatGarminDoesntHave() {
        // Made up: a sleep score with no time asleep, calories eaten of 0 (nothing logged), an activity whose type
        // isn't in Garmin's list and has no duration, and a weigh-in with body fat but no weight.
        onTheEveningOfTheCapture();
        signedIn();
        garmin.respond(STEPS_RANGE, 200, """
                        {"data":[{"calendarDate":"2026-11-12","movement":{"steps":{"value":14239}},
                         "calories":{"consumedValue":0},
                         "wellnessChronology":{"endTimestampLocal":"2026-11-13T00:00:00.0"}}]}""")
                .respond(SLEEP_RANGE_PATH + "2026-11-12/2026-11-12", 200, """
                        {"individualStats":[{"calendarDate":"2026-11-12",
                         "values":{"sleepScore":50,"gmtSleepStartTimeInMillis":1794447708000}}]}""")
                .respond(WEIGHT_RANGE_PATH + "2026-11-12/2026-11-12", 200, """
                        {"dailyWeightSummaries":[{"summaryDate":"2026-11-12","allWeightMetrics":[
                         {"bodyFat":18.04,"timestampGMT":1794467400000}]}]}""")
                .respond(ACTIVITIES, 200, """
                        [{"activityId":7,"activityName":"Something new","activityType":{"typeKey":"new_sport_2030"},
                          "startTimeLocal":"2026-11-12 18:00:00","startTimeGMT":"2026-11-12 18:00:00"}]""")
                .respond(ACTIVITY_TYPES, 200, Fixtures.read("activity-types.json"))
                .respond(ACTIVITY_TYPES, 200, Fixtures.read("activity-types.json"));

        String reply = ok("get_daily_log", Map.of("to", "2026-11-12"));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-11-20","timeZone":"Europe/London","from":"2026-11-12","to":"2026-11-12","days":[
                 {"date":"2026-11-12","final":true,"sleep":{"score":50},"steps":14239,
                  "activities":[{"id":7,"name":"Something new","type":"new_sport_2030"}],
                  "weight":{"bodyFatPercent":18.0,"time":"07:10","weighIns":1}}]}"""));
    }

    @Test
    void dailyLogDefaultsToYesterdayAndADayWithNoWatchDataIsntFinal() {
        onTheEveningOfTheCapture();
        signedIn();
        nothingFor("2026-11-19", "2026-11-19");

        String reply = ok("get_daily_log", Map.of());

        // Garmin has nothing from the watch for it, and no later day, so the watch hasn't synced since.
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-11-20","timeZone":"Europe/London","from":"2026-11-19","to":"2026-11-19",
                 "days":[{"date":"2026-11-19","final":false,"watchDataUntil":"none"}]}"""));
        assertThat(garmin.requests(STEPS_RANGE).getFirst().queryParams())
                .isEqualTo(Map.of("startDate", "2026-11-19", "endDate", "2026-11-19"));
        // No activities, so their types aren't needed.
        assertThat(garmin.requests(ACTIVITY_TYPES)).isEmpty();
    }

    @Test
    void aPastDayTheWatchHasntSyncedSinceSaysWhereItsDataStops() {
        // As on 22 November 2026: the watch last synced at 08:43 on the 18th here. Today never has watchDataUntil.
        onTheEveningOfTheCapture();
        signedIn();
        garmin.respond(STEPS_RANGE, 200, """
                        {"data":[
                         {"calendarDate":"2026-11-17","movement":{"steps":{"value":11269}},
                          "wellnessChronology":{"endTimestampLocal":"2026-11-18T00:00:00.0"}},
                         {"calendarDate":"2026-11-18","movement":{"steps":{"value":95}},
                          "wellnessChronology":{"endTimestampLocal":"2026-11-18T08:42:00.0"}}]}""")
                .respond(SLEEP_RANGE_PATH + "2026-11-17/2026-11-20", 200, "{\"individualStats\":[]}")
                .respond(WEIGHT_RANGE_PATH + "2026-11-17/2026-11-20", 200, "{\"dailyWeightSummaries\":[]}")
                .respond(ACTIVITIES, 200, "[]");

        String reply = ok("get_daily_log", Map.of("from", "2026-11-17", "to", "2026-11-20"));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-11-20","timeZone":"Europe/London","from":"2026-11-17","to":"2026-11-20","days":[
                 {"date":"2026-11-17","final":true,"steps":11269},
                 {"date":"2026-11-18","final":false,"watchDataUntil":"08:42","steps":95},
                 {"date":"2026-11-19","final":false,"watchDataUntil":"none"},
                 {"date":"2026-11-20","final":false}]}"""));
    }

    @Test
    void dailyLogWithOnlyToIsThatOneDayAndOnlyFromGoesToYesterday() {
        onTheEveningOfTheCapture();
        signedIn();
        nothingFor("2026-11-12", "2026-11-12");
        nothingFor("2026-11-08", "2026-11-19");

        assertThat(json.readTree(ok("get_daily_log", Map.of("to", "2026-11-12"))).path("from").asString())
                .isEqualTo("2026-11-12");
        assertThat(json.readTree(ok("get_daily_log", Map.of("from", "2026-11-08"))).path("to").asString())
                .isEqualTo("2026-11-19");
        assertThat(garmin.requests(SLEEP_RANGE_PATH + "2026-11-12/2026-11-12")).hasSize(1);
        assertThat(garmin.requests(SLEEP_RANGE_PATH + "2026-11-08/2026-11-19")).hasSize(1);
    }

    @Test
    void dailyLogWithOnlyFromTodayIsJustTodayAndNotFinal() {
        onTheEveningOfTheCapture();
        signedIn();
        nothingFor("2026-11-20", "2026-11-20");

        String reply = ok("get_daily_log", Map.of("from", "2026-11-20"));

        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-11-20","timeZone":"Europe/London","from":"2026-11-20","to":"2026-11-20",
                 "days":[{"date":"2026-11-20","final":false}]}"""));
    }

    @Test
    void dailyLogsBadRangesAreRefusedWithNothingSent() {
        assertThat(error("get_daily_log", Map.of("from", "2026-11-20", "to", "2026-11-19")))
                .isEqualTo("`from` (2026-11-20) is after `to` (2026-11-19). Put the earlier date first.");
        // 29 days.
        assertThat(error("get_daily_log", Map.of("from", "2026-10-23", "to", "2026-11-20")))
                .isEqualTo("The range can be at most 28 days. 2026-10-23 to 2026-11-20 is 29 days, both included.");
        for (String date : List.of("2026-13-01", "2026-02-30", "23-10-2026", "yesterday", "2026-10-2")) {
            assertThat(error("get_daily_log", Map.of("from", date)))
                    .isEqualTo("`from` isn't a date. Dates look like 2026-10-22.");
            assertThat(error("get_daily_log", Map.of("from", "2026-11-01", "to", date)))
                    .isEqualTo("`to` isn't a date. Dates look like 2026-10-22.");
        }
        assertThat(error("get_daily_log", Map.of("date", "2026-11-19")))
                .isEqualTo("Unknown input `date`. get_daily_log takes from, to, refresh.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void dailyLogsDaysAfterTodayOrTooLongAgoWithNoToNeedOnlyTheProfile() {
        // Today comes from the profile's time zone, so that is asked for (once), but nothing else.
        onTheEveningOfTheCapture();
        signedIn();

        assertThat(error("get_daily_log", Map.of("from", "2026-11-21")))
                .isEqualTo("`from` (2026-11-21) is after today (2026-11-20). Garmin has nothing for it yet.");
        assertThat(error("get_daily_log", Map.of("to", "2026-11-21")))
                .isEqualTo("`to` (2026-11-21) is after today (2026-11-20). Garmin has nothing for it yet.");
        assertThat(error("get_daily_log", Map.of("from", "2026-11-14", "to", "2026-11-21")))
                .isEqualTo("`to` (2026-11-21) is after today (2026-11-20). Garmin has nothing for it yet.");
        // From there to yesterday is 29 days.
        assertThat(error("get_daily_log", Map.of("from", "2026-10-22")))
                .isEqualTo("The range can be at most 28 days. 2026-10-22 to 2026-11-19 is 29 days, both included.");

        assertThat(garmin.requests()).extracting(FakeGarmin.Request::path)
                .containsOnly(PROFILE_PATH, ToolFixtures.PERSONAL);
    }

    @Test
    void dailyLogRateLimitedOnceWithNoRetry() {
        onTheEveningOfTheCapture();
        signedIn();
        garmin.respond(STEPS_RANGE, 429, "");

        assertThat(error("get_daily_log", Map.of("from", "2026-10-24", "to", "2026-11-20")))
                .isEqualTo("Garmin is rate limiting. Wait a while (an hour or more) before trying again.");
        assertThat(garmin.requests(STEPS_RANGE)).hasSize(1);
        assertThat(garmin.requests()).extracting(FakeGarmin.Request::path)
                .containsOnly(PROFILE_PATH, ToolFixtures.PERSONAL, STEPS_RANGE);
        assertThat(log()).isEqualTo("WARN get_daily_log failed in N ms: Garmin is rate limiting. Wait a while "
                + "(an hour or more) before trying again.");
    }

    @Test
    void dailyLogRefreshAsksGarminAgain() {
        onTheEveningOfTheCapture();
        signedIn();
        nothingFor("2026-11-19", "2026-11-19");
        nothingFor("2026-11-19", "2026-11-19");

        ok("get_daily_log", Map.of());
        ok("get_daily_log", Map.of("refresh", true));
        ok("get_daily_log", Map.of("refresh", false));

        for (String path : List.of(STEPS_RANGE, SLEEP_RANGE_PATH + "2026-11-19/2026-11-19",
                WEIGHT_RANGE_PATH + "2026-11-19/2026-11-19", ACTIVITIES)) {
            assertThat(garmin.requests(path)).as(path).hasSize(2);
        }
    }

    // refresh

    @Test
    void refreshAsksGarminEvenWithAFreshCopyAndEachToolPassesItOn() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]").respond(ACTIVITIES, 200, "[]")
                .respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{}")
                .respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{}")
                .respond(SUMMARY, 200, "{\"totalSteps\":100}").respond(SUMMARY, 200, "{\"totalSteps\":200}")
                .respond(WEIGHT_PATH + "2026-10-24", 200, "{}").respond(WEIGHT_PATH + "2026-10-24", 200, "{}")
                .respond(SLEEP, 204, "").respond(SLEEP, 204, "")
                .respond(WEIGHT_RANGE_PATH + "2026-09-25/2026-10-24", 200, "{}")
                .respond(WEIGHT_RANGE_PATH + "2026-09-25/2026-10-24", 200, "{}");
        var calls = List.of(
                new HashMap<String, Object>(Map.of("tool", "get_activities")),
                new HashMap<String, Object>(Map.of("tool", "get_activity", "id", 24816092240L)),
                new HashMap<String, Object>(Map.of("tool", "get_daily_summary")),
                new HashMap<String, Object>(Map.of("tool", "get_sleep")),
                new HashMap<String, Object>(Map.of("tool", "get_weight")));

        var summaries = new ArrayList<String>();
        for (Map<String, Object> call : calls) {
            String tool = (String) call.remove("tool");
            var refreshed = new HashMap<>(call);
            refreshed.put("refresh", true);
            var notRefreshed = new HashMap<>(call);
            notRefreshed.put("refresh", false);
            for (Map<String, Object> arguments : List.of(call, refreshed, notRefreshed)) {
                String reply = ok(tool, arguments);
                if (tool.equals("get_daily_summary")) {
                    summaries.add(json.readTree(reply).path("steps").asString());
                }
            }
        }

        // Asked, then asked again with refresh, then answered from the refreshed copy.
        assertThat(summaries).containsExactly("100", "200", "200");
        for (String path : List.of(ACTIVITIES, ACTIVITY, SPLITS, SUMMARY, WEIGHT_PATH + "2026-10-24", SLEEP,
                WEIGHT_RANGE_PATH + "2026-09-25/2026-10-24")) {
            assertThat(garmin.requests(path)).as(path).hasSize(2);
        }
        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
    }

    // Bad inputs: an error the AI can act on, and nothing sent to Garmin

    @Test
    void badDates() {
        for (String date : List.of("2026-13-01", "2026-02-30", "23-10-2026", "yesterday", "2026-10-2")) {
            assertThat(error("get_activities", Map.of("from", date)))
                    .isEqualTo("`from` isn't a date. Dates look like 2026-10-22.");
            assertThat(error("get_activities", Map.of("to", date)))
                    .isEqualTo("`to` isn't a date. Dates look like 2026-10-22.");
            assertThat(error("get_daily_summary", Map.of("date", date)))
                    .isEqualTo("`date` isn't a date. Dates look like 2026-10-22.");
            assertThat(error("get_sleep", Map.of("date", date)))
                    .isEqualTo("`date` isn't a date. Dates look like 2026-10-22.");
            assertThat(error("get_weight", Map.of("from", date)))
                    .isEqualTo("`from` isn't a date. Dates look like 2026-10-22.");
            assertThat(error("get_weight", Map.of("from", "2026-10-01", "to", date)))
                    .isEqualTo("`to` isn't a date. Dates look like 2026-10-22.");
        }
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void fromAfterTo() {
        assertThat(error("get_activities", Map.of("from", "2026-10-23", "to", "2026-10-22")))
                .isEqualTo("`from` (2026-10-23) is after `to` (2026-10-22). Put the earlier date first.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void moreThanNinetyDays() {
        assertThat(error("get_activities", Map.of("from", "2026-07-26", "to", "2026-10-24")))
                .isEqualTo("The range can be at most 90 days. 2026-07-26 to 2026-10-24 is 91 days, both included.");
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void aFromAfterTodayOrTooLongAgoWithNoToNeedsOnlyTheProfile() {
        // Today comes from the profile's time zone, so that is asked for (once), but no activities.
        signedIn();

        assertThat(error("get_activities", Map.of("from", "2026-10-25")))
                .isEqualTo("`from` (2026-10-25) is after today (2026-10-24).");
        assertThat(error("get_activities", Map.of("from", "2026-07-26")))
                .isEqualTo("The range can be at most 90 days. 2026-07-26 to 2026-10-24 is 91 days, both included.");

        assertThat(garmin.requests(ACTIVITIES)).isEmpty();
    }

    @Test
    void badActivityIds() {
        assertThat(error("get_activity", Map.of()))
                .isEqualTo("`id` is required: a number from get_activities, like 24810777073.");
        for (long id : List.of(0L, -5L)) {
            assertThat(error("get_activity", Map.of("id", id)))
                    .isEqualTo("`id` is a number from get_activities, like 24810777073.");
        }
        assertThat(garmin.requests()).isEmpty();
    }

    @Test
    void unknownInputs() {
        assertThat(error("get_activities", Map.of("days", 30)))
                .isEqualTo("Unknown input `days`. get_activities takes from, to, type, refresh.");
        assertThat(error("get_sleep", Map.of("night", "2026-10-23")))
                .isEqualTo("Unknown input `night`. get_sleep takes date, refresh.");
        assertThat(error("get_weight", Map.of("days", 30)))
                .isEqualTo("Unknown input `days`. get_weight takes from, to, refresh.");
        assertThat(garmin.requests()).isEmpty();
    }

    // Errors: an error result in our own words, said once

    @Test
    void notLoggedIn() {
        tools();
        deleteTokenFile();

        assertThat(error("get_sleep", Map.of())).isEqualTo("Not logged in. Run `stride-mcp login`.");
        assertThat(garmin.requests()).isEmpty();
        // The log line says what the AI was told.
        assertThat(log()).isEqualTo("WARN get_sleep failed in N ms: Not logged in. Run `stride-mcp login`.");
    }

    @Test
    void sessionExpired() {
        // Garmin turns the access token down, then the refresh token.
        garmin.respond(PROFILE_PATH, 401, "").respond(FakeGarmin.TOKEN_PATH, 401, "{\"error\":\"invalid_grant\"}");

        assertThat(error("get_activities", Map.of())).isEqualTo("Session expired. Run `stride-mcp login`.");
        assertThat(garmin.requests(FakeGarmin.TOKEN_PATH)).hasSize(1);
    }

    @Test
    void noActivityWithThatIdPointsToGetActivitiesNotTheCli() {
        garmin.respond(ACTIVITY, 404, "");

        assertThat(error("get_activity", Map.of("id", 24816092240L)))
                .isEqualTo("No activity with id 24816092240. Check the id with get_activities.");
        assertThat(log()).isEqualTo("WARN get_activity failed in N ms: No activity with id 24816092240. Check the "
                + "id with get_activities.");
    }

    @Test
    void forbiddenOnceWithNoRetry() {
        garmin.respond(PROFILE_PATH, 403, "<html>blocked</html>");

        assertThat(error("get_sleep", Map.of())).isEqualTo("Garmin refused the request during the request to "
                + PROFILE_PATH + " (HTTP 403). It may be blocking this connection. Wait a while before trying again.");
        assertThat(garmin.requests()).hasSize(1);
    }

    @Test
    void rateLimitedOnceWithNoRetry() {
        garmin.respond(PROFILE_PATH, 429, "");

        assertThat(error("get_daily_summary", Map.of()))
                .isEqualTo("Garmin is rate limiting. Wait a while (an hour or more) before trying again.");
        assertThat(garmin.requests()).hasSize(1);
        assertThat(log()).isEqualTo("WARN get_daily_summary failed in N ms: Garmin is rate limiting. Wait a while "
                + "(an hour or more) before trying again.");
    }

    @Test
    void garminWouldNotShareTheDailySummary() {
        signedIn();
        garmin.respond(SUMMARY, 200, "{\"privacyProtected\":true}");

        assertThat(error("get_daily_summary", Map.of()))
                .isEqualTo("Garmin wouldn't share the summary for 2026-10-24. Run `stride-mcp login` again.");
        assertThat(garmin.requests(WEIGHT_PATH + "2026-10-24")).isEmpty();
    }

    @Test
    void anUnexpectedReply() {
        signedIn();
        garmin.respond(SLEEP, 200, "[1, 2]");

        assertThat(error("get_sleep", Map.of("date", "2026-10-23"))).isEqualTo("Garmin's reply for the sleep for "
                + "the night ending 2026-10-23 wasn't what stride-mcp expected. Garmin may have changed it. Check for "
                + "an update to stride-mcp.");
    }

    @Test
    void anotherHttpErrorLeavesGarminsReplyOut() {
        // The CLI shows Garmin's error body. The AI and the log don't get it.
        signedIn();
        garmin.respond(SLEEP, 500, "{\"message\":\"internal detail\"}");

        assertThat(error("get_sleep", Map.of())).isEqualTo("Garmin returned HTTP 500 during the request to "
                + SLEEP.replace(" ", "%20") + ". Try again later.");
        assertThat(log()).isEqualTo("WARN get_sleep failed in N ms: Garmin returned HTTP 500 during the request "
                + "to " + SLEEP.replace(" ", "%20") + ". Try again later.");
    }

    @Test
    void moreThanTwoHundredActivities() {
        signedIn();
        for (int page = 0; page < 10; page++) {
            garmin.respond(ACTIVITIES, 200, IntStream.range(page * 20, page * 20 + 20)
                    .mapToObj(id -> "{\"activityId\":" + (id + 1) + "}").collect(Collectors.joining(",", "[", "]")));
        }

        assertThat(error("get_activities", Map.of("from", "2026-10-01", "to", "2026-10-24")))
                .isEqualTo("More than 200 activities from 2026-10-01 to 2026-10-24. Try a shorter range.");
    }

    @Test
    void aTokenFileProblem() throws Exception {
        tools();
        Path lockFile = temp.resolve("garmin_tokens.json.lock");
        Files.deleteIfExists(lockFile);
        Files.createDirectory(lockFile);

        // With the reason, which the cause's message alone doesn't always give, and what to do next.
        String told = "Couldn't open the lock file " + lockFile + " (is a directory). Check that "
                + lockFile.getParent() + " and the files in it are yours and can be read and written.";
        assertThat(error("get_activities", Map.of())).isEqualTo(told);
        assertThat(garmin.requests()).isEmpty();
        assertThat(log()).isEqualTo("WARN get_activities failed in N ms: " + told);
    }

    @Test
    void aBugOnlyPointsToTheLogAndItsDetailsStayThere() {
        // An UncheckedIOException that isn't the token file's, and a checked exception thrown sneakily, are bugs too.
        List<Throwable> bugs = List.of(new IllegalStateException("a detail only the log should have"),
                new UncheckedIOException(new IOException("a detail only the log should have")),
                new IOException("a detail only the log should have"));
        for (Throwable bug : bugs) {
            logged.list.clear();
            var api = garmin.signedInApi(temp.resolve("garmin_tokens.json"), clock);
            var cache = new GarminCache(temp.resolve("cache"), json, clock);
            var broken = new GarminClient(api, cache, GarminDataConfig.defaults(garmin.config()), json, clock,
                    ZoneOffset.UTC) {
                @Override
                public LocalDate today() {
                    throw GarminToolsTest.<RuntimeException>sneaky(bug);
                }
            };
            tools = new SyncMcpToolProvider(List.of(new GarminTools(broken, LOG_FILE))).getToolSpecifications();

            assertThat(error("get_sleep", Map.of())).as(bug.toString())
                    .isEqualTo("stride-mcp hit an unexpected error. Details are in " + LOG_FILE + ".");
            assertThat(log()).as(bug.toString()).startsWith("ERROR get_sleep failed in N ms\n"
                    + bug.getClass().getName() + ": ").contains("a detail only the log should have");
        }
    }

    /** Throws any exception, checked or not, as the compiler can't see. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable e) throws T {
        throw (T) e;
    }

    @Test
    void aCacheThatCantBeUsedIsLoggedOnceAndTheRepliesStillCome() throws Exception {
        Path cacheDir = temp.resolve("cache");
        Files.createDirectory(cacheDir);
        signedIn();
        garmin.respond(SLEEP, 204, "").respond(SLEEP, 204, "").respond(SLEEP, 204, "");

        String first = ok("get_sleep", Map.of());
        String second = ok("get_sleep", Map.of());
        // A different problem: a link where the folder was. Still not logged again.
        Files.delete(cacheDir);
        Files.createSymbolicLink(cacheDir, temp);
        String third = ok("get_sleep", Map.of());

        assertThat(first).isEqualTo(second).isEqualTo(third).doesNotContain("cache");
        // Nothing cached, so Garmin is asked every time.
        assertThat(garmin.requests(SLEEP)).hasSize(3);
        assertThat(log()).isEqualTo("""
                INFO get_sleep worked in N ms
                WARN Not using the cache: %s already exists and wasn't made by stride-mcp. Pick a folder that \
                doesn't exist yet with --stride.cache-dir, and stride-mcp will create it. Further cache problems \
                aren't logged until stride-mcp restarts.
                INFO get_sleep worked in N ms
                INFO get_sleep worked in N ms""".formatted(cacheDir));
    }

    /** Calls the tool, checks it worked, and returns its one text reply. */
    private String ok(String tool, Map<String, Object> arguments) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s %s: %s", tool, arguments, result.content()).isNotEqualTo(Boolean.TRUE);
        return text(result);
    }

    /** Calls the tool, checks it was an error, and returns its message. */
    private String error(String tool, Map<String, Object> arguments) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s %s", tool, arguments).isTrue();
        return text(result);
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        SyncToolSpecification spec = tools().stream().filter(s -> s.tool().name().equals(tool)).findFirst()
                .orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(tool, arguments, null));
    }

    private String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        String text = ((TextContent) result.content().getFirst()).text();
        replies.add(text);
        return text;
    }

    /** The tools as Spring AI's annotation scanner registers them, on one client, as in a running server. */
    private List<SyncToolSpecification> tools() {
        if (tools == null) {
            var api = garmin.signedInApi(temp.resolve("garmin_tokens.json"), clock);
            var cache = new GarminCache(temp.resolve("cache"), json, clock);
            var client = new GarminClient(api, cache, GarminDataConfig.defaults(garmin.config()), json, clock,
                    ZoneOffset.UTC);
            tools = new SyncMcpToolProvider(List.of(new GarminTools(client, LOG_FILE))).getToolSpecifications();
        }
        return tools;
    }

    private void deleteTokenFile() {
        try {
            Files.delete(temp.resolve("garmin_tokens.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What GarminTools logged, one line per event (plus any stack trace), with the times as N. */
    private String log() {
        return logged.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage().replaceAll("\\d+ ms", "N ms")
                        + (event.getThrowableProxy() == null ? ""
                                : "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
    }

    private Tool tool(String name) {
        return tools().stream().map(SyncToolSpecification::tool).filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Tool tool, String name) {
        return (Map<String, Object>) ((Map<String, Object>) tool.inputSchema().get("properties")).get(name);
    }

    /**
     * The evening of the daily log's capture ({@link ToolFixtures#LOG_NOW}): today is 20 November. Call before
     * anything else.
     */
    private void onTheEveningOfTheCapture() {
        clock = Clock.fixed(LOG_NOW, ZoneOffset.UTC);
    }

    /** Queues a daily log from {@code from} to {@code to} with nothing in it: no steps, sleep, weigh-ins or activities. */
    private void nothingFor(String from, String to) {
        garmin.respond(STEPS_RANGE, 200, "{\"data\":[]}")
                .respond(SLEEP_RANGE_PATH + from + "/" + to, 200, "{\"individualStats\":[]}")
                .respond(WEIGHT_RANGE_PATH + from + "/" + to, 200, "{\"dailyWeightSummaries\":[]}")
                .respond(ACTIVITIES, 200, "[]");
    }

    /** Queues the profile and its time zone (Europe/London) once. The cache keeps them after that. */
    private void signedIn() {
        garmin.respond(PROFILE_PATH, 200, Fixtures.read("social-profile.json"))
                .respond(PERSONAL, 200, Fixtures.read("personal-information.json"));
    }
}
