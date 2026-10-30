package com.hammez.stride.mcp;

import java.time.Instant;

import com.hammez.stride.garmin.Fixtures;

import tools.jackson.databind.json.JsonMapper;

/**
 * What the tool tests share: the moment they run at, Garmin's paths as the fake sees them, and the replies pinned
 * from the fixtures. {@link GarminToolsTest} pins each reply; the side-by-side test and {@code ServeModeTest}
 * expect the same text. The replies are pretty JSON here: compare them with {@link #compact}.
 */
public final class ToolFixtures {

    /**
     * In the profile's time zone (Europe/London) it is already Sat 24 October (00:30), while in UTC it is still the
     * 23rd. So "today" is the 24th.
     */
    public static final Instant NOW = Instant.parse("2026-10-23T23:30:00Z");

    // Decoded, as Garmin sees them. The fixture's display name is "jo runner".
    public static final String PERSONAL = "/userprofile-service/userprofile/personal-information/jo runner";
    public static final String ACTIVITIES = "/activitylist-service/activities/search/activities";
    public static final String ACTIVITY = "/activity-service/activity/24816092240";
    public static final String SPLITS = "/activity-service/activity/24816092240/splits";
    public static final String SUMMARY = "/usersummary-service/usersummary/daily/jo runner";
    public static final String SLEEP = "/wellness-service/wellness/dailySleepData/jo runner";
    public static final String WEIGHT_PATH = "/weight-service/weight/dayview/";

    /** The log file the tools name in a bug's message. Nothing is written to it. */
    public static final String LOG_FILE = "/Users/owner/.stride-mcp/stride-mcp.log";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** get_activities with no inputs: the 8 activities in activities.json. */
    public static final String LAST_SEVEN_DAYS = """
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
               "movingDurationSeconds":4529,"averageHeartRateBpm":112,"maxHeartRateBpm":171,"kilocalories":502}]}""";

    /** get_activity for 24816092240: activity.json and activity-splits.json. */
    public static final String STAIR_STEPPER = """
            {"today":"2026-10-24","timeZone":"Europe/London","id":24816092240,"name":"Stair Stepper",
             "type":"stair_climbing","start":"2026-10-22T17:04","duration":"10:02","durationSeconds":602,
             "averageHeartRateBpm":142,"maxHeartRateBpm":158,"kilocalories":133,"aerobicTrainingEffect":2.0,
             "anaerobicTrainingEffect":0.0,"trainingLoad":22,
             "laps":[{"number":1,"duration":"10:02","durationSeconds":602,"averageHeartRateBpm":142,
               "maxHeartRateBpm":158}]}""";

    /** get_daily_summary for 2026-10-23: summary-today.json and {@link Fixtures#TWO_WEIGH_INS}. */
    public static final String DAY_WITH_TWO_WEIGH_INS = """
            {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,"steps":3532,
             "stepGoal":11190,"distanceKm":3.18,"totalKilocalories":1417,"activeKilocalories":222,
             "restingHeartRateBpm":45,"minHeartRateBpm":43,"maxHeartRateBpm":130,"averageStressLevel":20,
             "bodyBatteryLowest":30,"bodyBatteryHighest":96,"moderateIntensityMinutes":0,
             "vigorousIntensityMinutes":2,"floorsClimbed":7,
             "weight":{"kilograms":75.4,"weighIns":2,"bmi":23.3,"bodyFatPercent":18.6,"bodyWaterPercent":55.1,
               "muscleMassKilograms":33.1,"boneMassKilograms":3.2}}""";

    /** get_sleep for 2026-10-23: sleep-last-night.json. */
    public static final String LAST_NIGHT = """
            {"today":"2026-10-24","timeZone":"Europe/London","date":"2026-10-23","recorded":true,
             "start":"2026-10-22T22:37","end":"2026-10-23T07:37","asleep":"8:52:00","asleepSeconds":31920,
             "deepSleep":"1:31:00","deepSleepSeconds":5460,"lightSleep":"4:55:00","lightSleepSeconds":17700,
             "remSleep":"2:26:00","remSleepSeconds":8760,"awake":"0:08:00","awakeSeconds":480,"score":91,
             "quality":"Excellent","averageBreathsPerMinute":12.0,"averageSpo2Percent":97.0}""";

    /** Pretty JSON as one line, keeping its field order, to compare with a reply. */
    public static String compact(String pretty) {
        return JSON.writeValueAsString(JSON.readTree(pretty));
    }

    private ToolFixtures() {
    }
}
