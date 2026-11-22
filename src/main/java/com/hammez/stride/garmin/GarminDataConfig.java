package com.hammez.stride.garmin;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminAuthConfig;

/**
 * Everything about Garmin's data endpoints that Garmin may change: paths, query parameter names and paging.
 * Also how long the cache keeps each reply. Keep these values here and nowhere else. Based on python-garminconnect (checked October 2026;
 * the range calls and activity types 20 November 2026). Paths are templates: fill the {@code {…}} parts with
 * {@link #fill(String, String...)}.
 *
 * @param socialProfilePath       who I am, with {@code displayName}
 * @param personalInformationPath my profile's time zone, by {@code {displayName}}
 * @param activitiesPath          the activity search, newest first
 * @param activitiesStartDateParam first day of the search (YYYY-MM-DD)
 * @param activitiesEndDateParam  last day of the search (YYYY-MM-DD)
 * @param activitiesStartParam    paging offset
 * @param activitiesLimitParam    page size
 * @param activitiesTypeParam     optional activity type filter, such as {@code running}
 * @param activitiesPageSize      how many activities to ask for per page
 * @param activitiesPageLimit     the most pages to fetch for one search, so paging can't go on forever
 * @param activityPath            one activity, by {@code {activityId}}
 * @param activitySplitsPath      one activity's laps, by {@code {activityId}}
 * @param dailySummaryPath        one day's totals, by {@code {displayName}}
 * @param dailySummaryDateParam   the day (YYYY-MM-DD)
 * @param sleepPath               one night's sleep, by {@code {displayName}}
 * @param sleepDateParam          the morning the night ended (YYYY-MM-DD)
 * @param sleepBufferParam        how many minutes either side of sleep to include
 * @param sleepBufferMinutes      the value sent for {@code sleepBufferParam}
 * @param weighInsPath            one day's weigh-ins, by {@code {date}} (YYYY-MM-DD)
 * @param weighInsAllParam        asks for every weigh-in that day, not just one; see {@link #weighInsQuery()}
 * @param weighInsRangePath       the weigh-ins over a range, by {@code {from}} and {@code {to}} (YYYY-MM-DD, both
 *                                included). Sent with {@link #weighInsQuery()} too. No range limit in
 *                                python-garminconnect (checked 20 November 2026); ours is {@link #MAX_WEIGHT_RANGE_DAYS}.
 * @param dailySummariesPath      daily summaries over a range, oldest first. At most {@link #MAX_LOG_RANGE_DAYS}.
 * @param dailySummariesStartDateParam first day of the range (YYYY-MM-DD)
 * @param dailySummariesEndDateParam   last day of the range (YYYY-MM-DD)
 * @param sleepStatsPath          each night's sleep stats over a range, by {@code {from}} and {@code {to}}
 *                                (YYYY-MM-DD). Garmin allows at most 28 days (python-garminconnect, checked
 *                                20 November 2026), so {@link #MAX_LOG_RANGE_DAYS}.
 * @param activityTypesPath       Garmin's list of activity types, with each one's parent, for an activity's group
 *                                (phase 4b)
 * @param cacheLifetimes          how long the cache keeps each kind of reply
 */
public record GarminDataConfig(
        String socialProfilePath,
        String personalInformationPath,
        String activitiesPath,
        String activitiesStartDateParam,
        String activitiesEndDateParam,
        String activitiesStartParam,
        String activitiesLimitParam,
        String activitiesTypeParam,
        int activitiesPageSize,
        int activitiesPageLimit,
        String activityPath,
        String activitySplitsPath,
        String dailySummaryPath,
        String dailySummaryDateParam,
        String sleepPath,
        String sleepDateParam,
        String sleepBufferParam,
        int sleepBufferMinutes,
        String weighInsPath,
        String weighInsAllParam,
        String weighInsRangePath,
        String dailySummariesPath,
        String dailySummariesStartDateParam,
        String dailySummariesEndDateParam,
        String sleepStatsPath,
        String activityTypesPath,
        CacheLifetimes cacheLifetimes) {

    /**
     * How long {@link GarminCache} keeps a reply. "Today" is today in the Garmin profile's time zone.
     * The lifetime is decided when the reply is saved, so a day fetched while it was "yesterday" still only lasts
     * {@code recentDay}, even once it is older.
     *
     * @param profile          {@code displayName} and the time zone: they almost never change
     * @param activity         one activity, and its laps: finished activities rarely change (renaming is the usual edit)
     * @param olderActivities  an activity list whose range ends before yesterday: late uploads are possible but rare
     * @param recentActivities an activity list whose range includes today or yesterday: a new one can sync any time
     * @param recentDay        a daily summary, sleep or weigh-ins for today or yesterday: still changing as the watch
     *                         or scale syncs
     * @param olderDay         a daily summary, sleep or weigh-ins for an older day: finished
     * @param emptyOlderDay    an empty reply for an older day (no data, no sleep, no weigh-ins, no activities): a watch
     *                         or scale might sync late. Also a range (weigh-ins, steps or sleep) ending before
     *                         yesterday with nothing in it.
     * @param olderRange       a range of weigh-ins, steps or sleep ending before yesterday, with something in it:
     *                         shorter than {@code olderDay}, as a weight typed in later, or a late sync, for a day in
     *                         the range should show by the next day (phase 4c catches up the health log from these).
     *                         One day's replies have their own files.
     * @param activityTypes    Garmin's list of activity types: it hardly ever changes (phase 4b)
     */
    public record CacheLifetimes(
            Duration profile,
            Duration activity,
            Duration olderActivities,
            Duration recentActivities,
            Duration recentDay,
            Duration olderDay,
            Duration emptyOlderDay,
            Duration olderRange,
            Duration activityTypes) {
    }

    /**
     * The longest date range, in days with both ends included, that stride-mcp asks Garmin for in one search.
     * Our rule, not Garmin's, so it's a constant rather than a field: the CLI's {@code --days} and the MCP tools'
     * {@code from}/{@code to} both check against it, and so does {@link GarminClient#activities}.
     */
    public static final int MAX_RANGE_DAYS = 90;

    /**
     * The longest range of weigh-ins, in days with both ends included, asked for in one search: a year to the same
     * date, as it's one request to Garmin whatever the range (phase 4a). That is 366 days with both ends counted, or
     * 367 when it includes 29 February (2027-03-01 to 2028-03-01), so any such year fits. Our rule, like
     * {@link #MAX_RANGE_DAYS}.
     */
    public static final int MAX_WEIGHT_RANGE_DAYS = 367;

    /**
     * The longest range, in days with both ends included, for the daily log ({@link GarminClient#dailyLog}) and the
     * range calls it makes for steps and sleep. Garmin's own limit on the sleep stats (python-garminconnect, checked
     * 20 November 2026), so each kind of data is one request per call (phase 4b). Our rule, like
     * {@link #MAX_RANGE_DAYS}.
     */
    public static final int MAX_LOG_RANGE_DAYS = 28;

    /**
     * The date range, in days with today included, used when none is given: the CLI's {@code activities} without
     * {@code --days}, and {@code get_activities} without {@code from} and {@code to}.
     */
    public static final int DEFAULT_RANGE_DAYS = 7;

    /**
     * The range of weigh-ins, in days with today included, used when none is given: the CLI's {@code weight} and
     * {@code get_weight} without {@code from} and {@code to}. Enough for a trend (phase 4a).
     */
    public static final int DEFAULT_WEIGHT_RANGE_DAYS = 30;

    /**
     * The daily log's range, in days, used when no {@code from} is given: the CLI's {@code log} and
     * {@code get_daily_log}. With no dates it ends yesterday, the day just finished, which is the one to log (phase
     * 4b). With only {@code to}, it ends there (the owner's choice, 22 November 2026). The tool's description and the
     * usage text say "yesterday" and "that one day", so they need changing with it.
     */
    public static final int DEFAULT_LOG_RANGE_DAYS = 1;

    /**
     * The query for {@code weighInsPath} and {@code weighInsRangePath}: every weigh-in, not one a day. The client and
     * {@code capture} both send this.
     */
    public Map<String, String> weighInsQuery() {
        return Map.of(weighInsAllParam, "true");
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z]+}");

    /** The social profile path is the one the login's profile check uses, so it comes from {@code auth}. */
    public static GarminDataConfig defaults(GarminAuthConfig auth) {
        return new GarminDataConfig(
                auth.profilePath(),
                "/userprofile-service/userprofile/personal-information/{displayName}",
                "/activitylist-service/activities/search/activities",
                "startDate",
                "endDate",
                "start",
                "limit",
                "activityType",
                20,
                10,
                "/activity-service/activity/{activityId}",
                "/activity-service/activity/{activityId}/splits",
                "/usersummary-service/usersummary/daily/{displayName}",
                "calendarDate",
                "/wellness-service/wellness/dailySleepData/{displayName}",
                "date",
                "nonSleepBufferMinutes",
                60,
                "/weight-service/weight/dayview/{date}",
                "includeAll",
                "/weight-service/weight/range/{from}/{to}",
                "/usersummary-service/v2/dailysummaries",
                "startDate",
                "endDate",
                "/sleep-service/stats/sleep/daily/{from}/{to}",
                "/activity-service/activity/activityTypes",
                new CacheLifetimes(
                        Duration.ofDays(7),
                        Duration.ofDays(7),
                        Duration.ofDays(1),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofHours(1),
                        Duration.ofDays(1),
                        Duration.ofDays(30)));
    }

    /**
     * Fills a path template's {@code {…}} parts with values, in order, each encoded with
     * {@link GarminApi#segment(String)} so an id or display name can't change which address is called.
     *
     * @throws IllegalArgumentException if the template doesn't have exactly one {@code {…}} part per value
     */
    public static String fill(String template, String... values) {
        Matcher placeholder = PLACEHOLDER.matcher(template);
        var path = new StringBuilder();
        int end = 0;
        int filled = 0;
        while (placeholder.find()) {
            if (filled == values.length) {
                throw new IllegalArgumentException("More {…} parts than values in " + template);
            }
            path.append(template, end, placeholder.start()).append(GarminApi.segment(values[filled++]));
            end = placeholder.end();
        }
        if (filled < values.length) {
            throw new IllegalArgumentException("Fewer {…} parts than values in " + template);
        }
        return path.append(template.substring(end)).toString();
    }
}
