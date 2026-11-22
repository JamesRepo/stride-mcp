package com.hammez.stride.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

import com.hammez.stride.garmin.ActivityDetails;
import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.LogRange;
import com.hammez.stride.garmin.UnexpectedReplyException;
import com.hammez.stride.garmin.WeighIn;
import com.hammez.stride.garmin.WeightHistory;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;
import com.hammez.stride.garmin.auth.TokenFileException;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The six MCP tools, found by Spring AI's annotation scanner (serve mode only, see {@link McpBeans}). Each checks
 * its input first, so a bad one never reaches Garmin, then asks {@link GarminClient} and returns
 * {@link ToolReplies}' JSON as text. All read-only.
 * <p>
 * Every exception is an error result with our own message, said once, so Spring AI's default text (the message
 * twice) is never used: a bad input, Garmin's errors, and a token file problem. Anything else is a bug: its message
 * only says where the log is, and its details stay in the log. {@link Error}s (out of memory, stack overflow) are
 * left to the server: carrying on after one isn't safe.
 * <p>
 * One log line per call: the tool, how long it took, and whether it worked, with the message the AI got if it
 * didn't. Never the inputs or Garmin's replies. The first cache problem is logged once per run, not told to the
 * AI: the reply is still right, and the fix is a setting in the AI app's server config.
 */
class GarminTools {

    private static final Logger log = LoggerFactory.getLogger(GarminTools.class);

    // The numbers come from GarminDataConfig, so the descriptions can't drift from the checks. GarminToolsTest
    // checks that the example range is MAX_RANGE_DAYS long, and that REFRESH, GET_WEIGHT and GET_DAILY_LOG match the
    // cache lifetimes.
    static final String GET_ACTIVITIES = """
            Lists the owner's Garmin Connect activities (runs, rides, strength sessions, walks and others) that \
            started between two dates, newest first. For each: id, name, type, local start time, distance in km, \
            time, moving time, average pace per km, elevation gain in metres, average and max heart rate in bpm, \
            and calories. Use it for questions about training over a period, such as "how was my training this \
            week?" or "how far did I run in September?". Then use get_activity with an id for laps, cadence, power \
            and training effect. `from` and `to` are dates like 2026-10-22, both included, covering at most \
            """ + GarminDataConfig.MAX_RANGE_DAYS + " days (for example 2026-07-26 to 2026-10-23). Leave both out "
            + "for the last " + GarminDataConfig.DEFAULT_RANGE_DAYS + " days. The reply also gives today's date in "
            + "the owner's time zone.";

    static final String GET_ACTIVITY = """
            One Garmin activity in detail: start time, type, distance, time and moving time, pace and fastest pace, \
            elevation gain, heart rate, cadence, power, calories, aerobic and anaerobic training effect (0 to 5), \
            training load, and each lap's distance, time, pace, heart rate and elevation. Fields Garmin didn't \
            record are left out. `id` is a number from get_activities.""";

    static final String GET_DAILY_SUMMARY = """
            The owner's totals for one day from Garmin: steps and step goal, distance, calories (total and active), \
            resting, minimum and maximum heart rate, average stress (0 to 100), lowest and highest Body Battery \
            (0 to 100), moderate and vigorous intensity minutes, and floors climbed. If the owner weighed in that \
            day, also the day's first weight in kg and the first body composition: BMI, body fat and body water in \
            %, muscle and bone mass in kg, visceral fat rating and metabolic age. Fields Garmin didn't record are \
            left out. `date` is like 2026-10-22, in the owner's time zone. Leave it out for today. Today's numbers \
            grow as the watch syncs. For weight over several days, use get_weight.""";

    static final String GET_SLEEP = """
            The owner's sleep for the night that ended on the morning of `date`: when it started and ended, time \
            asleep, deep, light and REM sleep, time awake, the sleep score (0 to 100) with Garmin's label, average \
            breathing rate and average SpO2. `date` is like 2026-10-23, which means the night of 22 to 23 October. \
            Leave it out for last night.""";

    static final String GET_WEIGHT = """
            The owner's weight and body composition from Garmin (a Garmin scale, or a weight typed in on Garmin \
            Connect): one entry per day with a weigh-in, oldest first. Days with no weigh-in are left out. For each \
            day: date, time of the weigh-in that gave the weight (local, like 07:37), weightKg in kg, \
            bodyFatPercent and bodyWaterPercent in % (18.5 means 18.5%), muscleMassKg and boneMassKg in kg, bmi, \
            visceralFat (Garmin's rating), metabolicAge in years, and weighIns (how many that day). A day's weight \
            is its first weigh-in, and its body composition the first scale reading (a weight typed in has only a \
            BMI); bodyCompositionFromAnotherWeighIn is true when they differ. Fields the scale didn't send are left \
            out. `summary` gives the first and last weight, changeKg \
            (last minus first), the lowest and highest, and how many days had a weigh-in: use it rather than adding \
            up the days yourself. Use this tool for weight over time, such as "how has my weight changed since \
            September?". get_daily_summary gives one day's weight with that day's other totals. `from` and `to` \
            are dates like 2026-09-01, both included, in the owner's time zone, covering at most \
            """ + GarminDataConfig.MAX_WEIGHT_RANGE_DAYS + " days, so a year to the same date fits. Leave both "
            + "out for the last " + GarminDataConfig.DEFAULT_WEIGHT_RANGE_DAYS + " days, today included. A range "
            + "ending before yesterday is cached for a day, or an hour if it had no weigh-ins. The reply also gives "
            + "today's date in the owner's time zone.";

    // "Yesterday" and "that one day" are DEFAULT_LOG_RANGE_DAYS (1): GarminToolsTest checks it.
    static final String GET_DAILY_LOG = """
            Everything Garmin has for each day of the owner's daily health log, one entry per day, oldest first. \
            Use it to fill in or catch up a log, such as "fill in my health log for yesterday", or for "what did \
            Garmin record last week?". For each day: date; final; watchDataUntil (see below); sleep: the night \
            that ended on the morning of that date (2026-11-12's sleep is the night of 11 to 12 November), with its \
            score (0 to 100) and hours (time asleep); steps; caloriesEaten; activities: each one that started that \
            day in the owner's time zone, oldest first, with id, name, type (Garmin's type key, such as \
            treadmill_running), group and minutes (the whole duration, not the moving time); and weight: kg (the \
            day's first weight), bodyFatPercent (18.5 means 18.5%) and muscleMassKg from the day's first scale \
            reading, time (local, like 07:37) of the weigh-in that gave the weight, or of the scale reading if no \
            weigh-in had a weight, and weighIns (how many that day). caloriesEaten is the food logged that day in \
            kcal (MyFitnessPal's daily total, as synced to Garmin). It is never calories burned. group is \
            Garmin's top-level type: running for running, trail_running and treadmill_running, cycling for \
            indoor_cycling, fitness_equipment for strength_training. Parts Garmin doesn't have are left out, so a \
            day with nothing is only its date and final. final: false means the day may still change, so don't log \
            it yet: it's today, or a past day the watch hasn't synced since. A past day that isn't final has \
            watchDataUntil: where Garmin's watch data for it stops (local, like 08:42, or none). Then ask the owner \
            to sync their watch, and call again with refresh. `from` and `to` are dates like 2026-11-08, both \
            included, in the owner's time zone, covering at most \
            """ + GarminDataConfig.MAX_LOG_RANGE_DAYS + " days and none after today. For more, call again for each "
            + GarminDataConfig.MAX_LOG_RANGE_DAYS + " days. Leave both out for yesterday. Only `from`: from there "
            + "to yesterday, or just today if `from` is today. Only `to`: that one day. A range ending before "
            + "yesterday is cached for up to a day once the watch has synced past it, otherwise its sleep, steps and "
            + "activities for up to 15 minutes. The reply also gives today's date in the owner's time zone.";

    static final String REFRESH = "Only set this when the owner says data is missing or they just synced their "
            + "watch, or an activity looks unfinished (Garmin was still processing it). Replies are cached: today's "
            + "and yesterday's data for 10 to 15 minutes, an activity's details for 7 days.";

    private final GarminClient garmin;
    private final String logFile;
    private final ToolReplies replies = new ToolReplies();
    private final AtomicBoolean cacheProblemLogged = new AtomicBoolean();

    /** @param logFile where serve mode logs, for the message about a bug */
    GarminTools(GarminClient garmin, String logFile) {
        this.garmin = garmin;
        this.logFile = logFile;
    }

    @McpTool(name = "get_activities", description = GET_ACTIVITIES,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))
    CallToolResult getActivities(
            @McpToolParam(required = false, description = "First day, like 2026-10-18, in the owner's time zone. "
                    + "Leave out from and to for the last " + GarminDataConfig.DEFAULT_RANGE_DAYS + " days.")
            String from,
            @McpToolParam(required = false, description = "Last day, like 2026-10-24, included. Leave out for "
                    + "today.") String to,
            @McpToolParam(required = false, description = "Garmin's activity type key, such as running, "
                    + "trail_running, cycling or strength_training. Leave out for all types.") String type,
            @McpToolParam(required = false, description = REFRESH) Boolean refresh,
            CallToolRequest request) {
        return call("get_activities", request, List.of("from", "to", "type", "refresh"), () -> {
            LocalDate first = date("from", from);
            LocalDate last = date("to", to);
            String typeKey = blank(type) ? null : type.strip().toLowerCase(Locale.ROOT);
            if (first != null && last != null) {
                checkRange(first, last, GarminDataConfig.MAX_RANGE_DAYS);
            }
            GarminClient client = client(refresh);
            LocalDate today = client.today();
            Range range = range(first, last, today, GarminDataConfig.DEFAULT_RANGE_DAYS,
                    GarminDataConfig.MAX_RANGE_DAYS);
            return replies.activities(client.activities(range.from(), range.to(), typeKey), range.from(),
                    range.to(), typeKey, today, client.zone());
        });
    }

    @McpTool(name = "get_activity", description = GET_ACTIVITY,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))
    CallToolResult getActivity(
            @McpToolParam(description = "The activity's id: a number from get_activities, like 24810777073.")
            Long id,
            @McpToolParam(required = false, description = REFRESH) Boolean refresh,
            CallToolRequest request) {
        return call("get_activity", request, List.of("id", "refresh"), () -> {
            if (id == null) {
                throw new BadInput("`id` is required: a number from get_activities, like 24810777073.");
            }
            if (id <= 0) {
                throw new BadInput("`id` is a number from get_activities, like 24810777073.");
            }
            GarminClient client = client(refresh);
            ActivityDetails details;
            try {
                details = client.activity(id);
            } catch (NotFoundException e) {
                throw new NotFoundException(e.getMessage() + " Check the id with get_activities.", e);
            }
            return replies.activity(details, client.today(), client.zone());
        });
    }

    @McpTool(name = "get_daily_summary", description = GET_DAILY_SUMMARY,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))
    CallToolResult getDailySummary(
            @McpToolParam(required = false, description = "The day, like 2026-10-22, in the owner's time zone. "
                    + "Leave out for today.") String date,
            @McpToolParam(required = false, description = REFRESH) Boolean refresh,
            CallToolRequest request) {
        return call("get_daily_summary", request, List.of("date", "refresh"), () -> {
            LocalDate day = date("date", date);
            GarminClient client = client(refresh);
            LocalDate today = client.today();
            LocalDate which = day == null ? today : day;
            var totals = client.dailySummary(which);
            // As in the CLI's summary: if the weigh-ins can't be read, the totals still come back.
            List<WeighIn> weighIns = List.of();
            String weightLeftOut = null;
            try {
                weighIns = client.weighIns(which);
            } catch (UnexpectedReplyException | NotFoundException e) {
                weightLeftOut = e.getMessage();
            }
            return replies.day(totals, weighIns, weightLeftOut, today, client.zone());
        });
    }

    @McpTool(name = "get_sleep", description = GET_SLEEP,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))
    CallToolResult getSleep(
            @McpToolParam(required = false, description = "The morning the night ended, like 2026-10-23 for the "
                    + "night of 22 to 23 October. Leave out for last night.") String date,
            @McpToolParam(required = false, description = REFRESH) Boolean refresh,
            CallToolRequest request) {
        return call("get_sleep", request, List.of("date", "refresh"), () -> {
            LocalDate morning = date("date", date);
            GarminClient client = client(refresh);
            LocalDate today = client.today();
            LocalDate which = morning == null ? today : morning;
            return replies.sleep(which, client.sleep(which), today, client.zone());
        });
    }

    @McpTool(name = "get_weight", description = GET_WEIGHT,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))
    CallToolResult getWeight(
            @McpToolParam(required = false, description = "First day, like 2026-09-01, in the owner's time zone. "
                    + "Leave out from and to for the last " + GarminDataConfig.DEFAULT_WEIGHT_RANGE_DAYS + " days.")
            String from,
            @McpToolParam(required = false, description = "Last day, like 2026-11-20, included. Leave out for "
                    + "today.") String to,
            @McpToolParam(required = false, description = REFRESH) Boolean refresh,
            CallToolRequest request) {
        return call("get_weight", request, List.of("from", "to", "refresh"), () -> {
            LocalDate first = date("from", from);
            LocalDate last = date("to", to);
            if (first != null && last != null) {
                checkRange(first, last, GarminDataConfig.MAX_WEIGHT_RANGE_DAYS);
            }
            GarminClient client = client(refresh);
            LocalDate today = client.today();
            Range range = range(first, last, today, GarminDataConfig.DEFAULT_WEIGHT_RANGE_DAYS,
                    GarminDataConfig.MAX_WEIGHT_RANGE_DAYS);
            WeightHistory history = WeightHistory.of(client.weighIns(range.from(), range.to()));
            return replies.weight(history, range.from(), range.to(), today, client.zone());
        });
    }

    @McpTool(name = "get_daily_log", description = GET_DAILY_LOG,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false))
    CallToolResult getDailyLog(
            @McpToolParam(required = false, description = "First day, like 2026-11-08, in the owner's time zone. "
                    + "Leave out from and to for yesterday.") String from,
            @McpToolParam(required = false, description = "Last day, like 2026-11-14, included. No later than "
                    + "today.") String to,
            @McpToolParam(required = false, description = REFRESH) Boolean refresh,
            CallToolRequest request) {
        return call("get_daily_log", request, List.of("from", "to", "refresh"), () -> {
            LocalDate first = date("from", from);
            LocalDate last = date("to", to);
            if (first != null && last != null) {
                checkRange(first, last, GarminDataConfig.MAX_LOG_RANGE_DAYS);
            }
            GarminClient client = client(refresh);
            LocalDate today = client.today();
            Range range = logRange(first, last, today);
            return replies.log(client.dailyLog(range.from(), range.to()), range.from(), range.to(), today,
                    client.zone());
        });
    }

    /**
     * Runs one tool call: refuses inputs the tool doesn't take, then does the work and returns its reply as JSON
     * text. Any error becomes an error result with our message. Logs one line.
     */
    private CallToolResult call(String tool, CallToolRequest request, List<String> inputs, Supplier<Object> work) {
        long start = System.nanoTime();
        try {
            unknownInputs(tool, request, inputs);
            CallToolResult result = CallToolResult.builder().addTextContent(replies.json(work.get())).build();
            log.info("{} worked in {} ms", tool, millisSince(start));
            return result;
        } catch (BadInput e) {
            // The input stays out of the log, so not the message either.
            log.info("{} refused its input in {} ms", tool, millisSince(start));
            return error(e.getMessage());
        } catch (GarminException e) {
            // Its message says what to do next. Garmin's reply is kept apart from it (garminReply()).
            return failed(tool, start, e.getMessage());
        } catch (TokenFileException e) {
            return failed(tool, start, e.explained());
        } catch (Exception e) {
            // A bug. This log is the only place to find it, so it gets the stack trace. Tokens never reach this
            // layer, and Garmin's replies are only read inside garmin/. The AI only learns where the log is.
            // Exception, not RuntimeException: a checked exception thrown sneakily is a bug too.
            log.error("{} failed in {} ms", tool, millisSince(start), e);
            return error("stride-mcp hit an unexpected error. Details are in " + logFile + ".");
        } finally {
            logCacheProblem();
        }
    }

    private static CallToolResult failed(String tool, long start, String message) {
        log.warn("{} failed in {} ms: {}", tool, millisSince(start), message);
        return error(message);
    }

    /**
     * Once per run. A failed save names a different file each time, and the cache never forgets a problem, so
     * logging each new one would repeat it on every call.
     */
    private void logCacheProblem() {
        garmin.cacheProblem().ifPresent(problem -> {
            if (cacheProblemLogged.compareAndSet(false, true)) {
                log.warn("{} Further cache problems aren't logged until stride-mcp restarts.", problem);
            }
        });
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    private static void unknownInputs(String tool, CallToolRequest request, List<String> inputs) {
        Map<String, Object> arguments = request == null ? null : request.arguments();
        if (arguments == null) {
            return;
        }
        for (String name : arguments.keySet()) {
            if (!inputs.contains(name)) {
                throw new BadInput("Unknown input `" + name + "`. " + tool + " takes "
                        + String.join(", ", inputs) + ".");
            }
        }
    }

    private GarminClient client(Boolean refresh) {
        return Boolean.TRUE.equals(refresh) ? garmin.skippingCache() : garmin;
    }

    /** The date input, or null if it was left out (or blank, as some AI apps send for "no value"). */
    private static LocalDate date(String name, String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new BadInput("`" + name + "` isn't a date. Dates look like 2026-10-22.");
        }
    }

    /**
     * The range to ask for, from the inputs: both left out is the {@code defaultDays} ending today, only {@code to}
     * is the {@code defaultDays} ending there, and only {@code from} is from there to today.
     *
     * @param from null if left out
     * @param to   null if left out
     */
    private static Range range(LocalDate from, LocalDate to, LocalDate today, int defaultDays, int maxDays) {
        if (to == null) {
            if (from != null && from.isAfter(today)) {
                throw new BadInput("`from` (" + from + ") is after today (" + today + ").");
            }
            to = today;
        }
        if (from == null) {
            from = to.minusDays(defaultDays - 1);
        }
        checkRange(from, to, maxDays);
        return new Range(from, to);
    }

    /**
     * The daily log's range, from the inputs, as {@link LogRange} works it out, then checked against the limit.
     *
     * @param from null if left out
     * @param to   null if left out
     */
    private static Range logRange(LocalDate from, LocalDate to, LocalDate today) {
        LogRange range;
        try {
            range = LogRange.of(from, to, today);
        } catch (LogRange.AfterToday e) {
            throw new BadInput("`" + e.input() + "` (" + e.date() + ") is after today (" + e.today() + "). Garmin "
                    + "has nothing for it yet.");
        }
        checkRange(range.from(), range.to(), GarminDataConfig.MAX_LOG_RANGE_DAYS);
        return new Range(range.from(), range.to());
    }

    private static void checkRange(LocalDate from, LocalDate to, int maxDays) {
        if (from.isAfter(to)) {
            throw new BadInput("`from` (" + from + ") is after `to` (" + to + "). Put the earlier date first.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maxDays) {
            throw new BadInput("The range can be at most " + maxDays + " days. " + from + " to " + to + " is "
                    + days + " days, both included.");
        }
    }

    /** Both days included. */
    private record Range(LocalDate from, LocalDate to) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** An input the tool can't use. Its message is what the AI sees. Nothing has been sent to Garmin. */
    private static final class BadInput extends RuntimeException {

        BadInput(String message) {
            super(message);
        }
    }
}
