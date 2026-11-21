package com.hammez.stride.garmin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hammez.stride.files.PrivateFolder;
import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;
import com.hammez.stride.garmin.auth.UnexpectedStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code capture} command's calls: saves Garmin's raw replies to files, to turn into test fixtures.
 * Each request is made once, with no cache and no retries. Each file holds the HTTP status and the reply
 * body, never headers or tokens.
 * <p>
 * The last four calls (the range calls and the activity types, added 20 November 2026 for phases 4a and 4b) are
 * saved raw, never parsed, and one of them failing doesn't stop the others: it goes in {@link Capture#failures()}.
 * All four were seen in the owner's capture of 20 November 2026. The weight range is parsed by the client; the
 * other three are settled in {@code docs/phases/phase-4b-daily-log.md} but not parsed yet.
 */
public class GarminSamples {

    static final String SOCIAL_PROFILE = "social-profile.json";
    static final String PERSONAL_INFORMATION = "personal-information.json";
    static final String ACTIVITIES = "activities.json";
    static final String ACTIVITIES_EMPTY = "activities-empty.json";
    static final String ACTIVITY = "activity.json";
    static final String ACTIVITY_SPLITS = "activity-splits.json";
    static final String ACTIVITY_NOT_FOUND = "activity-not-found.json";
    static final String SUMMARY_TODAY = "summary-today.json";
    static final String SUMMARY_PAST = "summary-past.json";
    static final String SLEEP_LAST_NIGHT = "sleep-last-night.json";
    static final String SLEEP_NONE = "sleep-none.json";
    static final String WEIGHT_TODAY = "weight-today.json";
    static final String WEIGHT_NONE = "weight-none.json";
    static final String WEIGHT_RANGE = "weight-range.json";
    static final String SUMMARY_RANGE = "summary-range.json";
    static final String SLEEP_RANGE = "sleep-range.json";
    static final String ACTIVITY_TYPES = "activity-types.json";

    /** Every file capture can write, in the order it writes them. */
    static final List<String> FILES = List.of(SOCIAL_PROFILE, PERSONAL_INFORMATION, ACTIVITIES, ACTIVITIES_EMPTY,
            ACTIVITY, ACTIVITY_SPLITS, ACTIVITY_NOT_FOUND, SUMMARY_TODAY, SUMMARY_PAST, SLEEP_LAST_NIGHT, SLEEP_NONE,
            WEIGHT_TODAY, WEIGHT_NONE, WEIGHT_RANGE, SUMMARY_RANGE, SLEEP_RANGE, ACTIVITY_TYPES);

    /** Days in {@link #WEIGHT_RANGE}, today included: phase 4a's default range. */
    static final int WEIGHT_RANGE_DAYS = 30;
    /**
     * Days in {@link #SUMMARY_RANGE} and {@link #SLEEP_RANGE}, today included: Garmin's limit for the sleep stats
     * (python-garminconnect's {@code get_sleep_daily}, checked 20 November 2026).
     */
    static final int DAILY_RANGE_DAYS = GarminDataConfig.MAX_LOG_RANGE_DAYS;

    /** A day long before Garmin Connect existed, so it should have no data. */
    private static final LocalDate EMPTY_DAY = LocalDate.of(2000, 1, 1);
    /** An activity id that shouldn't exist, for a 404 sample. */
    private static final String MISSING_ACTIVITY_ID = "1";

    private final GarminApi api;
    private final GarminDataConfig config;
    private final JsonMapper json;
    private final Clock clock;
    private final ZoneId fallbackZone;

    /** @param fallbackZone for "today" if the Garmin profile has no time zone (the computer's) */
    public GarminSamples(GarminApi api, GarminDataConfig config, JsonMapper json, Clock clock, ZoneId fallbackZone) {
        this.api = api;
        this.config = config;
        this.json = json;
        this.clock = clock;
        this.fallbackZone = fallbackZone;
    }

    /** One saved file and the HTTP status it holds. */
    public record Saved(String file, int status) {
    }

    /**
     * What a capture saved, and anything the user should know about it (such as skipped calls).
     *
     * @param failures the new calls that didn't get a 200 with JSON, one line each, naming the file. Their files are
     *                 still saved. Empty if they all worked.
     */
    public record Capture(Path folder, List<Saved> saved, List<String> notes, List<String> failures) {
    }

    /**
     * Makes the capture calls in order and saves one file per reply.
     * A 404 or an empty reply is saved and the run carries on. For the new calls (see the class comment), so is
     * any other HTTP error in Garmin's reply but 401, 403 and 429, and each of these, or a 200 that isn't JSON, is
     * listed in {@link Capture#failures()}. Any other error stops the run at once, including a failed token refresh
     * during a new call: it would fail again on the next one.
     *
     * @param folder created {@code 700} when the first file is saved, if it doesn't exist. An existing folder must
     *               already be {@code 700} and hold no samples from an earlier run.
     * @throws GarminException if the folder can't be used (before any call to Garmin), or if the run stopped,
     *                         saying why and which files were saved
     */
    public Capture capture(Path folder) {
        var out = new PrivateFolder(folder);
        check(out);
        for (String file : FILES) {
            if (Files.exists(out.file(file), LinkOption.NOFOLLOW_LINKS)) {
                throw new GarminException(out.dir() + " already holds samples from an earlier run (" + file + "). "
                        + "Delete them or pick a new folder.");
            }
        }
        var run = new Run(out);
        try {
            run.all();
        } catch (GarminException | UncheckedIOException e) {
            throw run.stopped(e);
        }
        return new Capture(out.dir(), List.copyOf(run.saved), List.copyOf(run.notes), List.copyOf(run.failures));
    }

    /**
     * Checks the folder can be used, without creating anything. A missing folder is fine. An existing one must be
     * a real folder that is already {@code 700}: capture never changes the permissions of a folder you already had.
     */
    private static void check(PrivateFolder out) {
        if (!out.exists()) {
            return;
        }
        try {
            if (!out.isPrivate()) {
                throw new GarminException("Folder " + out.dir() + " already exists and isn't private (700). "
                        + "Pick a new folder and stride-mcp will create it.");
            }
        } catch (NotDirectoryException e) {
            throw new GarminException("Folder " + out.dir() + " is a link or not a folder. Pick a new folder.");
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(out, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read the permissions of " + out.dir(), e);
        }
    }

    /**
     * Writes a {@code 600} file atomically. The folder is only created by the first write, as {@code 700}, so a run
     * that fails early leaves nothing.
     */
    private static void write(PrivateFolder out, String name, byte[] content) {
        check(out);
        try {
            if (!out.exists()) {
                out.create();
            }
            out.write(name, content);
        } catch (AtomicMoveNotSupportedException e) {
            throw new GarminException("Can't save files safely: " + out.dir() + " doesn't support atomic moves.", e);
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(out, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save " + out.file(name), e);
        }
    }

    private static GarminException noUnixPermissions(PrivateFolder out, UnsupportedOperationException e) {
        return new GarminException("Can't keep files private: " + out.dir()
                + " doesn't support Unix file permissions. Use a folder on your main disk.", e);
    }

    /** One capture run: the calls in order, and what has been saved so far. */
    private final class Run {

        private final PrivateFolder out;
        private final List<Saved> saved = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        /** The file being fetched, or null between files. */
        private String current;
        /** The error message saved with the last file, or null if it wasn't an error. */
        private String message;

        Run(PrivateFolder out) {
            this.out = out;
        }

        void all() {
            JsonNode profile = fetch(SOCIAL_PROFILE, config.socialProfilePath(), Map.of());
            String displayName = profile.path("displayName").asString("");
            if (displayName.isBlank()) {
                throw new GarminException(lastStatus() == 404
                        ? "Garmin has nothing at the profile address (HTTP 404), so capture can't ask for the daily "
                                + "data. Garmin may have moved it. Check for an update to stride-mcp."
                        : "Garmin's profile reply had no displayName, so capture can't ask for the daily data. "
                                + "Check that `stride-mcp whoami` works, then try again.");
            }

            JsonNode personal = fetch(PERSONAL_INFORMATION,
                    GarminDataConfig.fill(config.personalInformationPath(), displayName), Map.of());
            LocalDate today = today(personal);

            JsonNode activities = fetch(ACTIVITIES, config.activitiesPath(),
                    activitiesQuery(today.minusDays(6), today));
            int activitiesStatus = lastStatus();
            fetch(ACTIVITIES_EMPTY, config.activitiesPath(), activitiesQuery(EMPTY_DAY, EMPTY_DAY.plusDays(6)));

            // Activities come newest first.
            JsonNode newestId = activities.path(0).path("activityId");
            if (newestId.isIntegralNumber()) {
                String id = String.valueOf(newestId.asLong());
                fetch(ACTIVITY, GarminDataConfig.fill(config.activityPath(), id), Map.of());
                fetch(ACTIVITY_SPLITS, GarminDataConfig.fill(config.activitySplitsPath(), id), Map.of());
            } else if (activities.isArray() && activities.isEmpty()) {
                notes.add("No activities in the last 7 days, so " + ACTIVITY + " and " + ACTIVITY_SPLITS
                        + " were skipped. To get them, run capture into a new folder after your next activity syncs.");
            } else {
                notes.add(ACTIVITIES + (activities.isArray()
                        ? " had no activityId stride-mcp could read"
                        : " wasn't a list of activities (HTTP " + activitiesStatus + ")")
                        + ", so " + ACTIVITY + " and " + ACTIVITY_SPLITS + " were skipped.");
            }
            fetch(ACTIVITY_NOT_FOUND, GarminDataConfig.fill(config.activityPath(), MISSING_ACTIVITY_ID), Map.of());

            String summaryPath = GarminDataConfig.fill(config.dailySummaryPath(), displayName);
            fetch(SUMMARY_TODAY, summaryPath, Map.of(config.dailySummaryDateParam(), today.toString()));
            fetch(SUMMARY_PAST, summaryPath, Map.of(config.dailySummaryDateParam(), today.minusDays(30).toString()));

            String sleepPath = GarminDataConfig.fill(config.sleepPath(), displayName);
            fetch(SLEEP_LAST_NIGHT, sleepPath, sleepQuery(today));
            fetch(SLEEP_NONE, sleepPath, sleepQuery(EMPTY_DAY));

            fetch(WEIGHT_TODAY, GarminDataConfig.fill(config.weighInsPath(), today.toString()), config.weighInsQuery());
            fetch(WEIGHT_NONE, GarminDataConfig.fill(config.weighInsPath(), EMPTY_DAY.toString()),
                    config.weighInsQuery());

            // Saved raw, and one failing doesn't stop the rest. All four were seen 20 November 2026.
            LocalDate weightFrom = today.minusDays(WEIGHT_RANGE_DAYS - 1);
            fetchNew(WEIGHT_RANGE,
                    GarminDataConfig.fill(config.weighInsRangePath(), weightFrom.toString(), today.toString()),
                    config.weighInsQuery());
            LocalDate dailyFrom = today.minusDays(DAILY_RANGE_DAYS - 1);
            fetchNew(SUMMARY_RANGE, config.dailySummariesPath(), Map.of(
                    config.dailySummariesStartDateParam(), dailyFrom.toString(),
                    config.dailySummariesEndDateParam(), today.toString()));
            fetchNew(SLEEP_RANGE,
                    GarminDataConfig.fill(config.sleepStatsPath(), dailyFrom.toString(), today.toString()), Map.of());
            fetchNew(ACTIVITY_TYPES, config.activityTypesPath(), Map.of());
        }

        /** Today in the Garmin profile's time zone, or the computer's if the profile has none we can read. */
        private LocalDate today(JsonNode personal) {
            Optional<ZoneId> zone = GarminReplies.timeZone(personal);
            if (zone.isPresent()) {
                LocalDate today = LocalDate.now(clock.withZone(zone.get()));
                notes.add("Today is " + today + " in " + zone.get() + " (from your Garmin profile).");
                return today;
            }
            LocalDate today = LocalDate.now(clock.withZone(fallbackZone));
            notes.add("Today is " + today + " in " + fallbackZone + " (this computer's time zone: "
                    + PERSONAL_INFORMATION + " had none stride-mcp could read).");
            return today;
        }

        /** The status saved for the last file. */
        private int lastStatus() {
            return saved.getLast().status();
        }

        private Map<String, String> activitiesQuery(LocalDate from, LocalDate to) {
            return Map.of(
                    config.activitiesStartDateParam(), from.toString(),
                    config.activitiesEndDateParam(), to.toString(),
                    config.activitiesStartParam(), "0",
                    config.activitiesLimitParam(), String.valueOf(config.activitiesPageSize()));
        }

        private Map<String, String> sleepQuery(LocalDate date) {
            return Map.of(
                    config.sleepDateParam(), date.toString(),
                    config.sleepBufferParam(), String.valueOf(config.sleepBufferMinutes()));
        }

        /**
         * One request, saved to one file. Returns the reply's JSON, or a missing node if it was empty, not JSON
         * or a 404. GarminApi returns "" for HTTP 204, so an empty reply is saved as 204.
         */
        private JsonNode fetch(String file, String path, Map<String, String> query) {
            return fetch(file, path, query, false);
        }

        /**
         * One of the new calls: as {@link #fetch(String, String, Map)}, but an HTTP error in Garmin's reply other
         * than 401, 403 and 429 is saved too, and anything but a 200 with JSON goes in {@link #failures}. A failed
         * token refresh still stops the run (it isn't an {@link UnexpectedStatusException}).
         */
        private void fetchNew(String file, String path, Map<String, String> query) {
            JsonNode reply = fetch(file, path, query, true);
            int status = lastStatus();
            if (status != 200) {
                // GarminApi gives "" for a 204 and for an empty 200 alike, so don't name a status.
                failures.add(file + ": " + (status == 204 ? "Garmin's reply was empty." : message));
            } else if (reply.isMissingNode()) {
                failures.add(file + ": Garmin's reply wasn't JSON.");
            }
        }

        /** @param saveErrors also save an {@link UnexpectedStatusException}, rather than stopping the run */
        private JsonNode fetch(String file, String path, Map<String, String> query, boolean saveErrors) {
            current = file;
            message = null;
            ObjectNode content = json.createObjectNode();
            JsonNode reply = json.missingNode();
            try {
                String body = api.get(path, query);
                reply = parse(body);
                content.put("status", body.isEmpty() ? 204 : 200);
                if (reply.isMissingNode()) {
                    content.put("body", body);
                } else {
                    content.set("body", reply);
                }
            } catch (NotFoundException e) {
                // GarminApi doesn't hand over a 404's body, so save its message instead.
                message = e.getMessage();
                content.put("status", 404);
                content.put("message", message);
            } catch (UnexpectedStatusException e) {
                if (!saveErrors) {
                    throw e;
                }
                // Garmin's reply, with its secrets already hidden, may say what was wrong (a range too long, say).
                // Not the exception's message: its "Try again later" is wrong for a 400, and the folder can't be
                // used again anyway.
                message = "Garmin returned HTTP " + e.status() + " for " + path
                        + ". Its reply, in the file, may say why.";
                content.put("status", e.status());
                content.put("message", message);
                e.garminReply().ifPresent(garminReply -> content.put("body", garminReply));
            }
            write(out, file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
            saved.add(new Saved(file, content.path("status").asInt()));
            current = null;
            return reply;
        }

        private JsonNode parse(String body) {
            if (body.isBlank()) {
                return json.missingNode();
            }
            try {
                return json.readTree(body);
            } catch (JacksonException e) {
                return json.missingNode();
            }
        }

        /** The run's error, plus where it stopped and what was saved before. */
        GarminException stopped(RuntimeException e) {
            String reason = e instanceof UncheckedIOException io && io.getCause() != null
                    ? io.getMessage() + ": " + io.getCause().getMessage()
                    : e.getMessage();
            String where = current == null ? "Capture stopped." : "Capture stopped at " + current + ".";
            String what = saved.isEmpty()
                    ? "Nothing was saved."
                    : "Saved before stopping, in " + out.dir() + ": "
                            + String.join(", ", saved.stream().map(Saved::file).toList()) + ".";
            return new GarminException(reason + System.lineSeparator() + where + " " + what, e);
        }
    }
}
