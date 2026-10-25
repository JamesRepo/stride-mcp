package com.hammez.stride.garmin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import com.hammez.stride.garmin.GarminDataConfig.CacheLifetimes;
import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads my Garmin data: activities, one activity's details, daily summaries, sleep and weigh-ins.
 * Reads {@link GarminCache} first, and calls Garmin through {@link GarminApi} only when there's no fresh copy,
 * so it never sees a token. Lifetimes are in {@link CacheLifetimes}. Errors are never cached.
 * <p>
 * Every method can also throw the {@link GarminApi} errors: not logged in, session expired, 403, 429,
 * other HTTP errors and no connection.
 */
public class GarminClient {

    private static final String SOCIAL_PROFILE = "social-profile";
    private static final String PERSONAL_INFORMATION = "personal-information";

    private final GarminApi api;
    private final GarminCache cache;
    private final GarminDataConfig config;
    private final JsonMapper json;
    private final Clock clock;
    private final ZoneId fallbackZone;
    private final boolean skipCache;
    /** Shared with the {@link #skippingCache()} view. */
    private final AtomicReference<Remembered> remembered;

    /** @param fallbackZone for "today" if the Garmin profile has no time zone (the computer's) */
    public GarminClient(GarminApi api, GarminCache cache, GarminDataConfig config, JsonMapper json, Clock clock,
            ZoneId fallbackZone) {
        this(api, cache, config, json, clock, fallbackZone, false, new AtomicReference<>());
    }

    private GarminClient(GarminApi api, GarminCache cache, GarminDataConfig config, JsonMapper json, Clock clock,
            ZoneId fallbackZone, boolean skipCache, AtomicReference<Remembered> remembered) {
        this.api = api;
        this.cache = cache;
        this.config = config;
        this.json = json;
        this.clock = clock;
        this.fallbackZone = fallbackZone;
        this.skipCache = skipCache;
        this.remembered = remembered;
    }

    /**
     * The same client, except that it asks Garmin even if the cache has a fresh copy. The new reply is still cached.
     * For when I know something changed on Garmin ({@code --no-cache}). {@code displayName} and the time zone still
     * come from the cache: they only build addresses and decide what "today" is.
     */
    public GarminClient skippingCache() {
        return skipCache ? this : new GarminClient(api, cache, config, json, clock, fallbackZone, true, remembered);
    }

    /** The display name the daily endpoints need, and the time zone that decides what "today" is. */
    private record Me(String displayName, ZoneId zone) {
    }

    /** {@link Me}, and when it was looked up. */
    private record Remembered(Me me, Instant at) {
    }

    /**
     * Activities that started on {@code from} to {@code to} (both included), newest first.
     * Garmin pages the list; this stops at the first page that isn't full. Each page is cached as its own file,
     * but a search is only answered from the cache if every page it needs is there.
     *
     * @param type Garmin's type key, such as {@code running}, or null for all types
     * @return empty if there are none
     * @throws GarminException if there are more than the page limit allows (200)
     * @throws IllegalArgumentException if {@code from} is after {@code to}, or the range is longer than
     *         {@link GarminDataConfig#MAX_RANGE_DAYS}. Callers check their own input first; this is a safety net,
     *         so nothing is sent to Garmin.
     */
    public List<ActivitySummary> activities(LocalDate from, LocalDate to, String type) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from " + from + " is after to " + to + ". Put the earlier date first.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > GarminDataConfig.MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("from " + from + " to " + to + " is more than "
                    + GarminDataConfig.MAX_RANGE_DAYS + " days. Ask for at most " + GarminDataConfig.MAX_RANGE_DAYS
                    + " days, both ends included.");
        }
        String what = "the activities from " + from + " to " + to;
        String name = "activities-" + from + "-to-" + to + (type == null ? "" : "-" + fileSafe(type));
        if (!skipCache) {
            Optional<List<ActivitySummary>> copy = cachedActivities(name, what);
            if (copy.isPresent()) {
                return copy.get();
            }
        }
        // Needs "today", so the profile. Look it up before any page, so a profile error can't waste a whole download.
        boolean older = isOlder(to);
        var pages = new ArrayList<String>();
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
            String body = api.get(config.activitiesPath(), query);
            pages.add(body);
            if (addPage(body, what, found) < config.activitiesPageSize()) {
                Duration lifetime = older
                        ? (found.isEmpty() ? lifetimes().emptyOlderDay() : lifetimes().olderActivities())
                        : lifetimes().recentActivities();
                for (int i = 0; i < pages.size(); i++) {
                    cache.put(pageName(name, i), pages.get(i), lifetime);
                }
                return found;
            }
        }
        throw new GarminException("More than " + config.activitiesPageLimit() * config.activitiesPageSize()
                + " activities from " + from + " to " + to + ". Try a shorter range.");
    }

    /**
     * The search from the cache, or empty if a page it needs is missing, stale or broken. A broken page is deleted;
     * the pages fetched next replace the rest.
     */
    private Optional<List<ActivitySummary>> cachedActivities(String search, String what) {
        var found = new ArrayList<ActivitySummary>();
        for (int page = 0; page < config.activitiesPageLimit(); page++) {
            Optional<Integer> size = fromCache(List.of(pageName(search, page)),
                    bodies -> addPage(bodies.getFirst(), what, found));
            if (size.isEmpty()) {
                return Optional.empty();
            }
            if (size.get() < config.activitiesPageSize()) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /** Adds one page of the search to {@code found}. Returns how many activities the page had. */
    private int addPage(String body, String what, List<ActivitySummary> found) {
        JsonNode list = parse(body, what);
        if (list.isMissingNode()) {
            return 0;
        }
        if (!list.isArray()) {
            throw new UnexpectedReplyException(what);
        }
        for (JsonNode entry : list) {
            found.add(GarminReplies.listedActivity(entry, what));
        }
        return list.size();
    }

    private static String pageName(String search, int page) {
        return search + "-page-" + (page + 1);
    }

    /**
     * One activity, with its laps (2 calls, cached as 2 files).
     *
     * @throws NotFoundException if there's no activity with that id
     */
    public ActivityDetails activity(long id) {
        String key = String.valueOf(id);
        String name = "activity-" + id;
        String splitsName = name + "-splits";
        try {
            if (!skipCache) {
                Optional<ActivityDetails> copy = fromCache(List.of(name, splitsName),
                        bodies -> activity(bodies.get(0), bodies.get(1), id));
                if (copy.isPresent()) {
                    return copy.get();
                }
            }
            String activity = api.get(GarminDataConfig.fill(config.activityPath(), key), Map.of());
            String splits = api.get(GarminDataConfig.fill(config.activitySplitsPath(), key), Map.of());
            ActivityDetails details = activity(activity, splits, id);
            cache.put(name, activity, lifetimes().activity());
            cache.put(splitsName, splits, lifetimes().activity());
            return details;
        } catch (NotFoundException e) {
            throw new NotFoundException("No activity with id " + id + ". Check the id in `stride-mcp activities`.");
        }
    }

    private ActivityDetails activity(String activity, String splits, long id) {
        String what = "activity " + id;
        return GarminReplies.activity(parse(activity, what), parse(splits, "the laps of activity " + id), what);
    }

    /**
     * One day's totals. Every value is null for a day with no data.
     *
     * @throws GarminException if Garmin wouldn't share the day ({@code privacyProtected})
     */
    public DailySummary dailySummary(LocalDate date) {
        String what = "the daily summary for " + date;
        Me me = me();
        String path = GarminDataConfig.fill(config.dailySummaryPath(), me.displayName());
        DailySummary noData = GarminReplies.dailySummary(json.missingNode(), date, what);
        return cached("summary-" + date, !skipCache,
                () -> api.get(path, Map.of(config.dailySummaryDateParam(), date.toString())),
                body -> GarminReplies.dailySummary(parse(body, what), date, what),
                day -> dayLifetime(date, me, day.equals(noData)));
    }

    /** The night that ended on the morning of {@code date}, or empty if no sleep was recorded. */
    public Optional<Sleep> sleep(LocalDate date) {
        String what = "the sleep for the night ending " + date;
        Me me = me();
        String path = GarminDataConfig.fill(config.sleepPath(), me.displayName());
        return cached("sleep-" + date, !skipCache,
                () -> api.get(path, Map.of(
                        config.sleepDateParam(), date.toString(),
                        config.sleepBufferParam(), String.valueOf(config.sleepBufferMinutes()))),
                body -> GarminReplies.sleep(parse(body, what), date, what),
                night -> dayLifetime(date, me, night.isEmpty()));
    }

    /** The weigh-ins recorded on {@code date}, oldest first. Empty if there were none. */
    public List<WeighIn> weighIns(LocalDate date) {
        String what = "the weigh-ins for " + date;
        Me me = me();
        String path = GarminDataConfig.fill(config.weighInsPath(), date.toString());
        return cached("weight-" + date, !skipCache,
                () -> api.get(path, config.weighInsQuery()),
                body -> GarminReplies.weighIns(parse(body, what), date, what),
                found -> dayLifetime(date, me, found.isEmpty()));
    }

    /** Today's date in my Garmin profile's time zone, or the computer's if the profile has none. */
    public LocalDate today() {
        return today(me());
    }

    /** My Garmin profile's time zone, or the computer's if the profile has none. For showing times, such as sleep. */
    public ZoneId zone() {
        return me().zone();
    }

    private LocalDate today(Me me) {
        return LocalDate.now(clock.withZone(me.zone()));
    }

    /** True if the day is before yesterday, so its data should be finished. */
    private boolean isOlder(LocalDate date) {
        return date.isBefore(today().minusDays(1));
    }

    private Duration dayLifetime(LocalDate date, Me me, boolean empty) {
        if (!date.isBefore(today(me).minusDays(1))) {
            return lifetimes().recentDay();
        }
        return empty ? lifetimes().emptyOlderDay() : lifetimes().olderDay();
    }

    private CacheLifetimes lifetimes() {
        return config.cacheLifetimes();
    }

    /**
     * The display name and time zone. Remembered in memory for the profile's lifetime, so a run looks them up once,
     * even if the cache can't be used or the time zone can't be read.
     */
    private Me me() {
        Instant now = clock.instant();
        Remembered known = remembered.get();
        // A lookup "in the future" means the clock was turned back: look again.
        if (known != null && !now.isBefore(known.at()) && now.isBefore(known.at().plus(lifetimes().profile()))) {
            return known.me();
        }
        Me me = lookUpMe();
        remembered.set(new Remembered(me, now));
        return me;
    }

    /** The display name and time zone, from the cache if they were fetched in the last week, otherwise Garmin. */
    private Me lookUpMe() {
        String displayName = cached(SOCIAL_PROFILE, true,
                () -> api.get(config.socialProfilePath(), Map.of()),
                body -> displayName(parse(body, "your profile")),
                found -> lifetimes().profile());
        ZoneId zone = fallbackZone;
        try {
            zone = cached(PERSONAL_INFORMATION, true,
                    () -> api.get(GarminDataConfig.fill(config.personalInformationPath(), displayName), Map.of()),
                    body -> GarminReplies.timeZone(parse(body, "your time zone")),
                    found -> lifetimes().profile())
                    .orElse(fallbackZone);
        } catch (NotFoundException | UnexpectedReplyException e) {
            // No time zone we can read: use the computer's, as the plan says.
        }
        return new Me(displayName, zone);
    }

    private static String displayName(JsonNode profile) {
        JsonNode displayName = profile.path("displayName");
        if (!displayName.isString() || displayName.asString().isBlank()) {
            throw new UnexpectedReplyException("your profile");
        }
        return displayName.asString();
    }

    /**
     * One reply: from the cache if it has a fresh copy, otherwise from Garmin. A reply is only cached once it has
     * been read without error, so errors and replies we don't understand are never cached.
     *
     * @param useCache false to ask Garmin even if there's a fresh copy
     * @param lifetime how long to keep the new reply, decided from what it held
     */
    private <T> T cached(String name, boolean useCache, Supplier<String> fetch, Function<String, T> read,
            Function<T, Duration> lifetime) {
        if (useCache) {
            Optional<T> copy = fromCache(List.of(name), bodies -> read.apply(bodies.getFirst()));
            if (copy.isPresent()) {
                return copy.get();
            }
        }
        String body = fetch.get();
        T value = read.apply(body);
        cache.put(name, body, lifetime.apply(value));
        return value;
    }

    /**
     * What these saved replies read as, if every one is in the cache and fresh. If they don't read, they are deleted,
     * so they are asked for again even if the next request to Garmin fails.
     */
    private <T> Optional<T> fromCache(List<String> names, Function<List<String>, T> read) {
        var bodies = new ArrayList<String>();
        for (String name : names) {
            Optional<String> body = cache.get(name);
            if (body.isEmpty()) {
                return Optional.empty();
            }
            bodies.add(body.get());
        }
        try {
            return Optional.of(read.apply(bodies));
        } catch (GarminException e) {
            names.forEach(cache::remove);
            return Optional.empty();
        }
    }

    /**
     * A value made safe for a cache file name: a–z, 0–9 and {@code _} as they are (Garmin's type keys, such as
     * {@code trail_running}), anything else as {@code ~} and its UTF-8 bytes in hex.
     */
    private static String fileSafe(String value) {
        var safe = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            if ((b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '_') {
                safe.append((char) b);
            } else {
                safe.append('~').append(HexFormat.of().toHexDigits(b));
            }
        }
        return safe.toString();
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
