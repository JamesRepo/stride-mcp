package com.hammez.stride.garmin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminAuthConfig;

/**
 * Everything about Garmin's data endpoints that Garmin may change: paths, query parameter names and paging.
 * Keep these values here and nowhere else. Based on python-garminconnect (checked October 2026).
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
        int sleepBufferMinutes) {

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
                60);
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
