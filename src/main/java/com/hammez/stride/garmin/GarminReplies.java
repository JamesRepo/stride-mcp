package com.hammez.stride.garmin;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.hammez.stride.garmin.auth.GarminException;

import tools.jackson.databind.JsonNode;

/**
 * Turns Garmin's JSON replies into records. Field names were checked against real replies on 23 October 2026,
 * except where a comment says otherwise.
 * <p>
 * The overall shape is strict: a reply that isn't an object or list, or an activity without an id, is an
 * {@link UnexpectedReplyException}. Single fields are lenient: missing, null or the wrong type becomes null.
 */
final class GarminReplies {

    private GarminReplies() {
    }

    /** The profile's time zone ({@code timeZone}, also in {@code userInfo}), if it has one Java knows. */
    static Optional<ZoneId> timeZone(JsonNode personalInformation) {
        for (JsonNode zone : List.of(personalInformation.path("timeZone"),
                personalInformation.path("userInfo").path("timeZone"))) {
            if (zone.isString()) {
                try {
                    return Optional.of(ZoneId.of(zone.asString()));
                } catch (DateTimeException e) {
                    // Not a zone Java knows. Try the next place.
                }
            }
        }
        return Optional.empty();
    }

    /** One entry in the activity search. Its type is in {@code activityType}, its totals at the top level. */
    static ActivitySummary listedActivity(JsonNode entry, String what) {
        if (!entry.isObject()) {
            throw new UnexpectedReplyException(what);
        }
        return summary(entry, entry.path("activityType"), entry, what);
    }

    /**
     * One activity and its laps. The activity's type is in {@code activityTypeDTO}, its totals in {@code summaryDTO}.
     *
     * @param splits the laps reply, or a missing node if it was empty
     */
    static ActivityDetails activity(JsonNode activity, JsonNode splits, String what) {
        JsonNode totals = activity.path("summaryDTO");
        if (!activity.isObject() || !totals.isObject()) {
            throw new UnexpectedReplyException(what);
        }
        return new ActivityDetails(
                summary(activity, activity.path("activityTypeDTO"), totals, what),
                decimal(totals, "maxSpeed"),
                // Not seen yet: the sample was a stair stepper session. The list calls these
                // averageRunningCadenceInStepsPerMinute and avgPower, so accept either name.
                firstDecimal(totals, "averageRunCadence", "averageRunningCadenceInStepsPerMinute"),
                whole(firstDecimal(totals, "averagePower", "avgPower")),
                // trainingEffect is the aerobic one: it comes with aerobicTrainingEffectMessage.
                firstDecimal(totals, "trainingEffect", "aerobicTrainingEffect"),
                decimal(totals, "anaerobicTrainingEffect"),
                decimal(totals, "activityTrainingLoad"),
                laps(splits, what));
    }

    /**
     * One day's totals, or all nulls if Garmin had none (an empty reply).
     *
     * @throws GarminException if Garmin marked the day {@code privacyProtected}
     */
    static DailySummary dailySummary(JsonNode day, LocalDate date, String what) {
        if (!day.isMissingNode() && !day.isObject()) {
            throw new UnexpectedReplyException(what);
        }
        if (day.path("privacyProtected").asBoolean(false)) {
            throw new GarminException("Garmin wouldn't share the summary for " + date + ". Run `stride-mcp login` again.");
        }
        return new DailySummary(
                date,
                whole(day, "totalSteps"),
                whole(day, "dailyStepGoal"),
                decimal(day, "totalDistanceMeters"),
                whole(day, "totalKilocalories"),
                whole(day, "activeKilocalories"),
                whole(day, "restingHeartRate"),
                whole(day, "minHeartRate"),
                whole(day, "maxHeartRate"),
                percent(day, "averageStressLevel"),
                percent(day, "bodyBatteryHighestValue"),
                percent(day, "bodyBatteryLowestValue"),
                whole(day, "moderateIntensityMinutes"),
                whole(day, "vigorousIntensityMinutes"),
                decimal(day, "floorsAscended"));
    }

    /**
     * The night that ended on {@code date}, or empty if none was recorded. For a night with no sleep, Garmin
     * answers 200 with a {@code dailySleepDTO} whose fields are all null (seen October 2026), not 204.
     */
    static Optional<Sleep> sleep(JsonNode night, LocalDate date, String what) {
        if (night.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode dto = night.path("dailySleepDTO");
        if (!night.isObject() || !(dto.isObject() || dto.isMissingNode() || dto.isNull())) {
            throw new UnexpectedReplyException(what);
        }
        Instant start = epochMillis(dto, "sleepStartTimestampGMT");
        Integer sleepSeconds = whole(dto, "sleepTimeSeconds");
        if (start == null && sleepSeconds == null) {
            return Optional.empty();
        }
        JsonNode overall = dto.path("sleepScores").path("overall");
        return Optional.of(new Sleep(
                date,
                start,
                epochMillis(dto, "sleepEndTimestampGMT"),
                sleepSeconds,
                whole(dto, "deepSleepSeconds"),
                whole(dto, "lightSleepSeconds"),
                whole(dto, "remSleepSeconds"),
                whole(dto, "awakeSleepSeconds"),
                percent(overall, "value"),
                text(overall, "qualifierKey"),
                decimal(dto, "averageRespirationValue"),
                decimal(dto, "averageSpO2Value")));
    }

    private static ActivitySummary summary(JsonNode ids, JsonNode type, JsonNode totals, String what) {
        JsonNode id = ids.path("activityId");
        if (!id.isIntegralNumber()) {
            throw new UnexpectedReplyException(what);
        }
        return new ActivitySummary(
                id.asLong(),
                text(ids, "activityName"),
                text(type, "typeKey"),
                localDateTime(totals, "startTimeLocal", what),
                instant(totals, "startTimeGMT", what),
                decimal(totals, "distance"),
                decimal(totals, "duration"),
                decimal(totals, "movingDuration"),
                decimal(totals, "elevationGain"),
                decimal(totals, "averageSpeed"),
                whole(totals, "averageHR"),
                whole(totals, "maxHR"),
                whole(totals, "calories"));
    }

    /** Laps are in {@code lapDTOs}. No reply, or no list, means no laps. */
    private static List<Lap> laps(JsonNode splits, String what) {
        JsonNode list = splits.path("lapDTOs");
        if (list.isMissingNode() || list.isNull()) {
            return List.of();
        }
        if (!list.isArray()) {
            throw new UnexpectedReplyException(what);
        }
        var laps = new ArrayList<Lap>();
        for (JsonNode lap : list) {
            // Elevation per lap isn't in the sample (a stair stepper lap): to confirm with a run.
            laps.add(new Lap(
                    lap.path("lapIndex").isIntegralNumber() ? lap.path("lapIndex").asInt() : laps.size() + 1,
                    decimal(lap, "distance"),
                    decimal(lap, "duration"),
                    decimal(lap, "averageSpeed"),
                    whole(lap, "averageHR"),
                    whole(lap, "maxHR"),
                    decimal(lap, "elevationGain")));
        }
        return laps;
    }

    private static Double decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Double firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            Double value = decimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Garmin sends some counts as decimals ({@code "averageHR": 142.0}), so round them. */
    private static Integer whole(JsonNode node, String field) {
        return whole(decimal(node, field));
    }

    private static Integer whole(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    /** A 0–100 value. Anything outside that range is taken as "no data". */
    private static Integer percent(JsonNode node, String field) {
        Integer value = whole(node, field);
        return value == null || value < 0 || value > 100 ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    /** The list says {@code 2026-10-22 07:06:50}, one activity says {@code 2026-10-22T07:06:50.0}. */
    private static LocalDateTime localDateTime(JsonNode node, String field, String what) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new UnexpectedReplyException(what);
        }
    }

    /** Garmin's {@code …GMT} times are UTC without a zone. */
    private static Instant instant(JsonNode node, String field, String what) {
        LocalDateTime value = localDateTime(node, field, what);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static Instant epochMillis(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? Instant.ofEpochMilli(value.asLong()) : null;
    }
}
