package com.hammez.stride.garmin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads my Garmin data: activities, one activity's details, daily summaries and sleep.
 * Calls Garmin through {@link GarminApi}, so it never sees a token. No cache yet (step 5), except that
 * {@code displayName} and the time zone are fetched once per instance.
 * <p>
 * Every method can also throw the {@link GarminApi} errors: not logged in, session expired, 403, 429,
 * other HTTP errors and no connection.
 */
public class GarminClient {

    private final GarminApi api;
    private final GarminDataConfig config;
    private final JsonMapper json;
    private final Clock clock;
    private final ZoneId fallbackZone;
    private volatile Me me;

    /** @param fallbackZone for "today" if the Garmin profile has no time zone (the computer's) */
    public GarminClient(GarminApi api, GarminDataConfig config, JsonMapper json, Clock clock, ZoneId fallbackZone) {
        this.api = api;
        this.config = config;
        this.json = json;
        this.clock = clock;
        this.fallbackZone = fallbackZone;
    }

    /** The display name the daily endpoints need, and the time zone that decides what "today" is. */
    private record Me(String displayName, ZoneId zone) {
    }

    /**
     * Activities that started on {@code from} to {@code to} (both included), newest first.
     * Garmin pages the list; this stops at the first page that isn't full.
     *
     * @param type Garmin's type key, such as {@code running}, or null for all types
     * @return empty if there are none
     * @throws GarminException if there are more than the page limit allows (200)
     */
    public List<ActivitySummary> activities(LocalDate from, LocalDate to, String type) {
        String what = "the activities from " + from + " to " + to;
        var found = new ArrayList<ActivitySummary>();
        for (int page = 0; page < config.activitiesPageLimit(); page++) {
            var query = new HashMap<String, String>();
            query.put(config.activitiesStartDateParam(), from.toString());
            query.put(config.activitiesEndDateParam(), to.toString());
            query.put(config.activitiesStartParam(), String.valueOf(page * config.activitiesPageSize()));
            query.put(config.activitiesLimitParam(), String.valueOf(config.activitiesPageSize()));
            if (type != null) {
                query.put(config.activitiesTypeParam(), type);
            }
            JsonNode list = parse(api.get(config.activitiesPath(), query), what);
            if (list.isMissingNode()) {
                return found;
            }
            if (!list.isArray()) {
                throw new UnexpectedReplyException(what);
            }
            for (JsonNode entry : list) {
                found.add(GarminReplies.listedActivity(entry, what));
            }
            if (list.size() < config.activitiesPageSize()) {
                return found;
            }
        }
        throw new GarminException("More than " + config.activitiesPageLimit() * config.activitiesPageSize()
                + " activities from " + from + " to " + to + ". Try a shorter range.");
    }

    /**
     * One activity, with its laps (2 calls).
     *
     * @throws NotFoundException if there's no activity with that id
     */
    public ActivityDetails activity(long id) {
        String key = String.valueOf(id);
        String what = "activity " + id;
        try {
            JsonNode activity = parse(api.get(GarminDataConfig.fill(config.activityPath(), key), Map.of()), what);
            JsonNode splits = parse(api.get(GarminDataConfig.fill(config.activitySplitsPath(), key), Map.of()),
                    "the laps of activity " + id);
            return GarminReplies.activity(activity, splits, what);
        } catch (NotFoundException e) {
            throw new NotFoundException("No activity with id " + id + ". Check the id in `stride-mcp activities`.");
        }
    }

    /**
     * One day's totals. Every value is null for a day with no data.
     *
     * @throws GarminException if Garmin wouldn't share the day ({@code privacyProtected})
     */
    public DailySummary dailySummary(LocalDate date) {
        String what = "the daily summary for " + date;
        String path = GarminDataConfig.fill(config.dailySummaryPath(), me().displayName());
        JsonNode day = parse(api.get(path, Map.of(config.dailySummaryDateParam(), date.toString())), what);
        return GarminReplies.dailySummary(day, date, what);
    }

    /** The night that ended on the morning of {@code date}, or empty if no sleep was recorded. */
    public Optional<Sleep> sleep(LocalDate date) {
        String what = "the sleep for the night ending " + date;
        String path = GarminDataConfig.fill(config.sleepPath(), me().displayName());
        JsonNode night = parse(api.get(path, Map.of(
                config.sleepDateParam(), date.toString(),
                config.sleepBufferParam(), String.valueOf(config.sleepBufferMinutes()))), what);
        return GarminReplies.sleep(night, date, what);
    }

    /** Today's date in my Garmin profile's time zone, or the computer's if the profile has none. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(me().zone()));
    }

    private Me me() {
        Me known = me;
        if (known == null) {
            known = fetchMe();
            me = known;
        }
        return known;
    }

    private Me fetchMe() {
        JsonNode profile = parse(api.get(config.socialProfilePath(), Map.of()), "your profile");
        JsonNode displayName = profile.path("displayName");
        if (!displayName.isString() || displayName.asString().isBlank()) {
            throw new UnexpectedReplyException("your profile");
        }
        ZoneId zone = fallbackZone;
        try {
            String path = GarminDataConfig.fill(config.personalInformationPath(), displayName.asString());
            zone = GarminReplies.timeZone(parse(api.get(path, Map.of()), "your time zone")).orElse(fallbackZone);
        } catch (NotFoundException | UnexpectedReplyException e) {
            // No time zone we can read: use the computer's, as the plan says.
        }
        return new Me(displayName.asString(), zone);
    }

    /** The reply's JSON, or a missing node if it was empty (HTTP 204). */
    private JsonNode parse(String body, String what) {
        if (body.isBlank()) {
            return json.missingNode();
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new UnexpectedReplyException(what);
        }
    }
}
