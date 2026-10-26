package com.hammez.stride.mcp;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

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
 * the 24th.
 * <p>
 * What the server adds on top (the SDK checking inputs against the schema, the JSON-RPC around it) is in
 * ServeModeTest.
 */
class GarminToolsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T23:30:00Z");

    // Decoded, as Garmin sees them. The fixture's display name is "jo runner".
    private static final String PERSONAL = "/userprofile-service/userprofile/personal-information/jo runner";
    private static final String ACTIVITIES = "/activitylist-service/activities/search/activities";
    private static final String ACTIVITY = "/activity-service/activity/24816092240";
    private static final String SPLITS = "/activity-service/activity/24816092240/splits";
    private static final String SUMMARY = "/usersummary-service/usersummary/daily/jo runner";
    private static final String SLEEP = "/wellness-service/wellness/dailySleepData/jo runner";
    private static final String WEIGHT_PATH = "/weight-service/weight/dayview/";

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<String> replies = new ArrayList<>();
    private List<SyncToolSpecification> tools;

    @AfterEach
    void noTokenEverShows() {
        garmin.close();
        assertThat(String.join("\n", replies)).doesNotContain(FakeGarmin.ACCESS_TOKEN)
                .doesNotContain(FakeGarmin.REFRESH_TOKEN);
    }

    // What the AI sees in tools/list

    @Test
    void fourReadOnlyToolsWithThePlansDescriptions() {
        assertThat(tools()).extracting(spec -> spec.tool().name())
                .containsExactlyInAnyOrder("get_activities", "get_activity", "get_daily_summary", "get_sleep");
        assertThat(tool("get_activities").description()).isEqualTo(GarminTools.GET_ACTIVITIES);
        assertThat(tool("get_activity").description()).isEqualTo(GarminTools.GET_ACTIVITY);
        assertThat(tool("get_daily_summary").description()).isEqualTo(GarminTools.GET_DAILY_SUMMARY);
        assertThat(tool("get_sleep").description()).isEqualTo(GarminTools.GET_SLEEP);

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
    }

    @Test
    void theInputsTheAiSees() {
        assertThat(tool("get_activities").inputSchema().get("properties")).asInstanceOf(
                InstanceOfAssertFactories.MAP).containsOnlyKeys("from", "to", "type", "refresh");
        assertThat(property(tool("get_activities"), "from")).containsEntry("type", "string");
        assertThat(tool("get_activities").inputSchema().get("required")).isEqualTo(List.of());

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
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","from":"2026-10-18","to":"2026-10-24","count":8,
                 "activities":[
                  {"id":24816092240,"name":"Stair Stepper","type":"stair_climbing","start":"2026-10-22T17:04",
                   "duration":"10:02","durationSeconds":602,"averageHeartRateBpm":142,"maxHeartRateBpm":158,
                   "kilocalories":133},
                  {"id":24815932306,"name":"Indoor Cycling","type":"indoor_cycling","start":"2026-10-22T16:53",
                   "duration":"8:23","durationSeconds":503,"averageHeartRateBpm":120,"maxHeartRateBpm":151,
                   "kilocalories":80},
                  {"id":24810777073,"name":"Islington Running","type":"running","start":"2026-10-22T07:06",
                   "distanceKm":5.05,"duration":"36:01","durationSeconds":2161,"movingDuration":"35:51",
                   "movingDurationSeconds":2151,"pace":"7:08 /km","elevationGainMetres":18,"averageHeartRateBpm":143,
                   "maxHeartRateBpm":169,"kilocalories":448},
                  {"id":24801905256,"name":"Strength","type":"strength_training","start":"2026-10-21T11:48",
                   "duration":"1:10:03","durationSeconds":4203,"movingDuration":"1:10:03",
                   "movingDurationSeconds":4203,"averageHeartRateBpm":96,"maxHeartRateBpm":159,"kilocalories":334},
                  {"id":24793573630,"name":"Strength","type":"strength_training","start":"2026-10-20T17:18",
                   "duration":"55:59","durationSeconds":3359,"movingDuration":"55:59","movingDurationSeconds":3359,
                   "averageHeartRateBpm":79,"maxHeartRateBpm":146,"kilocalories":175},
                  {"id":24778243382,"name":"Strength","type":"strength_training","start":"2026-10-19T12:30",
                   "duration":"1:11:52","durationSeconds":4312,"movingDuration":"1:11:52",
                   "movingDurationSeconds":4312,"averageHeartRateBpm":93,"maxHeartRateBpm":166,"kilocalories":328},
                  {"id":24765567036,"name":"Islington Running","type":"running","start":"2026-10-18T10:07",
                   "distanceKm":8.01,"duration":"53:51","durationSeconds":3231,"movingDuration":"53:35",
                   "movingDurationSeconds":3215,"pace":"6:43 /km","elevationGainMetres":30,"averageHeartRateBpm":149,
                   "maxHeartRateBpm":164,"kilocalories":715},
                  {"id":24755552155,"name":"Strength","type":"strength_training","start":"2026-10-17T11:46",
                   "duration":"1:15:29","durationSeconds":4529,"movingDuration":"1:15:29",
                   "movingDurationSeconds":4529,"averageHeartRateBpm":112,"maxHeartRateBpm":171,"kilocalories":502}]}
                """));
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
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","id":24816092240,"name":"Stair Stepper",
                 "type":"stair_climbing","start":"2026-10-22T17:04","duration":"10:02","durationSeconds":602,
                 "averageHeartRateBpm":142,"maxHeartRateBpm":158,"kilocalories":133,"aerobicTrainingEffect":2.0,
                 "anaerobicTrainingEffect":0.0,"trainingLoad":22,
                 "laps":[{"number":1,"duration":"10:02","durationSeconds":602,"averageHeartRateBpm":142,
                   "maxHeartRateBpm":158}]}"""));
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
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,"steps":3532,
                 "stepGoal":11190,"distanceKm":3.18,"totalKilocalories":1417,"activeKilocalories":222,
                 "restingHeartRateBpm":45,"minHeartRateBpm":43,"maxHeartRateBpm":130,"averageStressLevel":20,
                 "bodyBatteryLowest":30,"bodyBatteryHighest":96,"moderateIntensityMinutes":0,
                 "vigorousIntensityMinutes":2,"floorsClimbed":7,
                 "weight":{"kilograms":75.4,"weighIns":2,"bmi":23.3,"bodyFatPercent":18.6,"bodyWaterPercent":55.1,
                   "muscleMassKilograms":33.1,"boneMassKilograms":3.2}}"""));
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
    void aWeightTypedInAfterASmartScaleSaysTheBodyCompositionIsFromAnotherWeighIn() {
        signedIn();
        garmin.respond(SUMMARY, 200, "{}").respond(WEIGHT_PATH + "2026-10-23", 200, """
                {"dateWeightList":[
                 {"weight":75900.0,"timestampGMT":1792785600000,"sourceType":"MANUAL"},
                 {"weight":75400.0,"bodyFat":18.6,"timestampGMT":1792737900000}]}""");

        String reply = ok("get_daily_summary", Map.of("date", "2026-10-23"));

        // Recorded, though the day's totals are empty: there was a weigh-in.
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,
                 "weight":{"kilograms":75.9,"weighIns":2,"bodyCompositionFromAnotherWeighIn":true,
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
        assertThat(reply).isEqualTo(compact("""
                {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,
                 "start":"2026-10-22T22:37","end":"2026-10-23T07:37","asleep":"8:52:00","asleepSeconds":31920,
                 "deepSleep":"1:31:00","deepSleepSeconds":5460,"lightSleep":"4:55:00","lightSleepSeconds":17700,
                 "remSleep":"2:26:00","remSleepSeconds":8760,"awake":"0:08:00","awakeSeconds":480,"score":91,
                 "quality":"Excellent","averageBreathsPerMinute":12.0,"averageSpo2Percent":97.0}"""));
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

    // refresh

    @Test
    void refreshAsksGarminEvenWithAFreshCopyAndEachToolPassesItOn() {
        signedIn();
        garmin.respond(ACTIVITIES, 200, "[]").respond(ACTIVITIES, 200, "[]")
                .respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{}")
                .respond(ACTIVITY, 200, Fixtures.read("activity.json")).respond(SPLITS, 200, "{}")
                .respond(SUMMARY, 200, "{\"totalSteps\":100}").respond(SUMMARY, 200, "{\"totalSteps\":200}")
                .respond(WEIGHT_PATH + "2026-10-24", 200, "{}").respond(WEIGHT_PATH + "2026-10-24", 200, "{}")
                .respond(SLEEP, 204, "").respond(SLEEP, 204, "");
        var calls = List.of(
                new HashMap<String, Object>(Map.of("tool", "get_activities")),
                new HashMap<String, Object>(Map.of("tool", "get_activity", "id", 24816092240L)),
                new HashMap<String, Object>(Map.of("tool", "get_daily_summary")),
                new HashMap<String, Object>(Map.of("tool", "get_sleep")));

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
        for (String path : List.of(ACTIVITIES, ACTIVITY, SPLITS, SUMMARY, WEIGHT_PATH + "2026-10-24", SLEEP)) {
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
        assertThat(garmin.requests()).isEmpty();
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
            tools = new SyncMcpToolProvider(List.of(new GarminTools(client))).getToolSpecifications();
        }
        return tools;
    }

    private Tool tool(String name) {
        return tools().stream().map(SyncToolSpecification::tool).filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Tool tool, String name) {
        return (Map<String, Object>) ((Map<String, Object>) tool.inputSchema().get("properties")).get(name);
    }

    /** Pretty JSON as one line, keeping its field order, to compare with a reply. */
    private String compact(String pretty) {
        return json.writeValueAsString(json.readTree(pretty));
    }

    /** Queues the profile and its time zone (Europe/London) once. The cache keeps them after that. */
    private void signedIn() {
        garmin.respond(PROFILE_PATH, 200, Fixtures.read("social-profile.json"))
                .respond(PERSONAL, 200, Fixtures.read("personal-information.json"));
    }
}
