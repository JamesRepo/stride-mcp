package com.hammez.stride.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.WeighIn;
import com.hammez.stride.garmin.UnexpectedReplyException;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The four MCP tools, found by Spring AI's annotation scanner (serve mode only, see {@link McpBeans}). Each checks
 * its input first, so a bad one never reaches Garmin, then asks {@link GarminClient} and returns
 * {@link ToolReplies}' JSON as text. All read-only.
 * <p>
 * A bad input is an error result with our own message. Other errors (Garmin's, and bugs) are still thrown, and
 * Spring AI turns them into an error result with the exception's message: step 5 of phase 3 replaces that.
 * <p>
 * One log line per call: the tool, how long it took, and whether it worked. Never its input or Garmin's reply.
 */
class GarminTools {

    private static final Logger log = LoggerFactory.getLogger(GarminTools.class);

    // The numbers come from GarminDataConfig, so the descriptions can't drift from the checks. GarminToolsTest
    // checks that the example range is MAX_RANGE_DAYS long, and that REFRESH matches the cache lifetimes.
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
            day, also the latest weight in kg and the latest body composition: BMI, body fat and body water in %, \
            muscle and bone mass in kg, visceral fat rating and metabolic age. Fields Garmin didn't record are left \
            out. `date` is like 2026-10-22, in the owner's time zone. Leave it out for today. Today's numbers grow \
            as the watch syncs.""";

    static final String GET_SLEEP = """
            The owner's sleep for the night that ended on the morning of `date`: when it started and ended, time \
            asleep, deep, light and REM sleep, time awake, the sleep score (0 to 100) with Garmin's label, average \
            breathing rate and average SpO2. `date` is like 2026-10-23, which means the night of 22 to 23 October. \
            Leave it out for last night.""";

    static final String REFRESH = "Only set this when the owner says data is missing or they just synced their "
            + "watch, or an activity looks unfinished (Garmin was still processing it). Replies are cached: today's "
            + "and yesterday's data for 10 to 15 minutes, an activity's details for 7 days.";

    private final GarminClient garmin;
    private final ToolReplies replies = new ToolReplies();

    GarminTools(GarminClient garmin) {
        this.garmin = garmin;
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
                checkRange(first, last);
            }
            GarminClient client = client(refresh);
            LocalDate today = client.today();
            if (last == null) {
                if (first != null && first.isAfter(today)) {
                    throw new BadInput("`from` (" + first + ") is after today (" + today + ").");
                }
                last = today;
            }
            if (first == null) {
                first = last.minusDays(GarminDataConfig.DEFAULT_RANGE_DAYS - 1);
            }
            checkRange(first, last);
            return replies.activities(client.activities(first, last, typeKey), first, last, typeKey, today,
                    client.zone());
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
            return replies.activity(client.activity(id), client.today(), client.zone());
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

    /**
     * Runs one tool call: refuses inputs the tool doesn't take, then does the work and returns its reply as JSON
     * text. Logs one line.
     */
    private CallToolResult call(String tool, CallToolRequest request, List<String> inputs, Supplier<Object> work) {
        long start = System.nanoTime();
        try {
            unknownInputs(tool, request, inputs);
            CallToolResult result = CallToolResult.builder().addTextContent(replies.json(work.get())).build();
            log.info("{} worked in {} ms", tool, millisSince(start));
            return result;
        } catch (BadInput e) {
            log.info("{} refused its input in {} ms", tool, millisSince(start));
            return CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
        } catch (GarminException e) {
            // Step 5 turns these into error results with our own wording. Until then Spring AI does.
            log.warn("{} failed in {} ms: {}", tool, millisSince(start), e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            // A bug. This log is the only place to find it, so it gets the stack trace. Tokens never reach this
            // layer, and Garmin's replies are only read inside garmin/.
            log.error("{} failed in {} ms", tool, millisSince(start), e);
            throw e;
        }
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

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadInput("`from` (" + from + ") is after `to` (" + to + "). Put the earlier date first.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > GarminDataConfig.MAX_RANGE_DAYS) {
            throw new BadInput("The range can be at most " + GarminDataConfig.MAX_RANGE_DAYS + " days. " + from
                    + " to " + to + " is " + days + " days, both included.");
        }
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
