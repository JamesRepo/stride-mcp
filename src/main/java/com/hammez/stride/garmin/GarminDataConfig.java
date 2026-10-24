package com.hammez.stride.garmin;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminAuthConfig;

/**
 * Everything about Garmin's data endpoints that Garmin may change: paths, query parameter names and paging.
 * Also how long the cache keeps each reply. Keep these values here and nowhere else. Based on python-garminconnect (checked October 2026).
 * Paths are templates: fill the {@code {…}} part with {@link #fill(String, String)}.
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
     * @param recentDay        a daily summary or sleep for today or yesterday: still changing as the watch syncs
     * @param olderDay         a daily summary or sleep for an older day: finished
     * @param emptyOlderDay    an empty reply for an older day (no data, no sleep, no activities): a watch might sync late
     */
    public record CacheLifetimes(
            Duration profile,
            Duration activity,
            Duration olderActivities,
            Duration recentActivities,
            Duration recentDay,
            Duration olderDay,
            Duration emptyOlderDay) {
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
                new CacheLifetimes(
                        Duration.ofDays(7),
                        Duration.ofDays(7),
                        Duration.ofDays(1),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofHours(1)));
    }

    /**
     * Fills a path template's one {@code {…}} part with a value, encoded with {@link GarminApi#segment(String)}
     * so an id or display name can't change which address is called.
     */
    public static String fill(String template, String value) {
        Matcher placeholder = PLACEHOLDER.matcher(template);
        if (!placeholder.find()) {
            throw new IllegalArgumentException("No {…} part in " + template);
        }
        return template.substring(0, placeholder.start()) + GarminApi.segment(value)
                + template.substring(placeholder.end());
    }
}
