package com.hammez.stride.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hammez.stride.garmin.ActivityDetails;
import com.hammez.stride.garmin.ActivitySummary;
import com.hammez.stride.garmin.DailySummary;
import com.hammez.stride.garmin.DayWeight;
import com.hammez.stride.garmin.Lap;
import com.hammez.stride.garmin.Sleep;
import com.hammez.stride.garmin.Units;
import com.hammez.stride.garmin.WeighIn;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns Garmin's records into the tools' replies, shaped for an AI reader (docs/phases/phase-3-mcp.md, "What comes
 * back"): km with 2 decimals, elevation in whole metres, durations as text and as seconds, pace as min:ss /km,
 * start times as local time to the minute. A value Garmin left out is left out of the JSON, not null.
 * <p>
 * Garmin sends 0 for the distance, speed and moving time of activities without them (strength training, indoor
 * cycling), so for activities and laps a 0 there is left out too.
 */
final class ToolReplies {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    // Fields in the records' order (today first), not sorted. Compact: an AI reads it as well, in fewer tokens.
    private final JsonMapper json = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .changeDefaultPropertyInclusion(include -> include.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    /** A reply as JSON text. */
    String json(Object reply) {
        return json.writeValueAsString(reply);
    }

    /** @param type the type asked for, or null for all */
    ActivitiesReply activities(List<ActivitySummary> found, LocalDate from, LocalDate to, String type,
            LocalDate today, ZoneId zone) {
        var activities = found.stream().map(a -> new ActivitiesReply.Activity(
                a.id(),
                a.name(),
                a.type(),
                minute(a.startLocal()),
                km(Units.positive(a.distanceMetres())),
                Units.duration(a.durationSeconds()),
                seconds(a.durationSeconds()),
                Units.duration(Units.positive(a.movingDurationSeconds())),
                seconds(Units.positive(a.movingDurationSeconds())),
                Units.pace(a.averageSpeedMetresPerSecond()),
                whole(a.elevationGainMetres()),
                a.averageHeartRateBpm(),
                a.maxHeartRateBpm(),
                a.kilocalories()))
                .toList();
        return new ActivitiesReply(today.toString(), zone.getId(), from.toString(), to.toString(), type,
                activities.size(), activities);
    }

    ActivityReply activity(ActivityDetails details, LocalDate today, ZoneId zone) {
        ActivitySummary a = details.summary();
        var laps = details.laps().stream().map(ToolReplies::lap).toList();
        return new ActivityReply(
                today.toString(),
                zone.getId(),
                a.id(),
                a.name(),
                a.type(),
                minute(a.startLocal()),
                km(Units.positive(a.distanceMetres())),
                Units.duration(a.durationSeconds()),
                seconds(a.durationSeconds()),
                Units.duration(Units.positive(a.movingDurationSeconds())),
                seconds(Units.positive(a.movingDurationSeconds())),
                Units.pace(a.averageSpeedMetresPerSecond()),
                Units.pace(details.maxSpeedMetresPerSecond()),
                whole(a.elevationGainMetres()),
                a.averageHeartRateBpm(),
                a.maxHeartRateBpm(),
                whole(details.averageCadenceStepsPerMinute()),
                details.averagePowerWatts(),
                a.kilocalories(),
                oneDecimal(details.aerobicTrainingEffect()),
                oneDecimal(details.anaerobicTrainingEffect()),
                whole(details.trainingLoad()),
                laps);
    }

    private static ActivityReply.Lap lap(Lap lap) {
        return new ActivityReply.Lap(
                lap.number(),
                km(Units.positive(lap.distanceMetres())),
                Units.duration(lap.durationSeconds()),
                seconds(lap.durationSeconds()),
                Units.pace(lap.averageSpeedMetresPerSecond()),
                lap.averageHeartRateBpm(),
                lap.maxHeartRateBpm(),
                whole(lap.elevationGainMetres()));
    }

    /**
     * @param weighIns      oldest first
     * @param weightLeftOut why the weigh-ins couldn't be read, or null
     */
    DayReply day(DailySummary day, List<WeighIn> weighIns, String weightLeftOut, LocalDate today, ZoneId zone) {
        DayReply.Weight weight = DayWeight.of(weighIns).map(ToolReplies::weight).orElse(null);
        if (weight == null && day.isEmpty()) {
            return DayReply.notRecorded(today.toString(), zone.getId(), day.date().toString(), weightLeftOut);
        }
        return new DayReply(
                today.toString(),
                zone.getId(),
                day.date().toString(),
                true,
                day.steps(),
                day.stepGoal(),
                km(day.distanceMetres()),
                day.totalKilocalories(),
                day.activeKilocalories(),
                day.restingHeartRateBpm(),
                day.minHeartRateBpm(),
                day.maxHeartRateBpm(),
                day.averageStressLevel(),
                day.bodyBatteryLowest(),
                day.bodyBatteryHighest(),
                day.moderateIntensityMinutes(),
                day.vigorousIntensityMinutes(),
                // Garmin counts part floors. Only whole ones were climbed.
                day.floorsClimbed() == null ? null : (long) Math.floor(day.floorsClimbed()),
                weight,
                weightLeftOut);
    }

    private static DayReply.Weight weight(DayWeight day) {
        WeighIn body = day.bodyComposition();
        return new DayReply.Weight(
                day.weight() == null ? null : oneDecimal(day.weight().weightKilograms()),
                day.weighIns(),
                day.fromDifferentWeighIns() ? Boolean.TRUE : null,
                body == null ? null : oneDecimal(body.bmi()),
                body == null ? null : oneDecimal(body.bodyFatPercent()),
                body == null ? null : oneDecimal(body.bodyWaterPercent()),
                body == null ? null : oneDecimal(body.muscleMassKilograms()),
                body == null ? null : oneDecimal(body.boneMassKilograms()),
                body == null ? null : body.visceralFat(),
                body == null ? null : body.metabolicAgeYears());
    }

    /**
     * @param date the morning the night ended
     * @param zone the owner's, for the start and end times
     */
    SleepReply sleep(LocalDate date, Optional<Sleep> found, LocalDate today, ZoneId zone) {
        if (found.isEmpty()) {
            return SleepReply.notRecorded(today.toString(), zone.getId(), date.toString());
        }
        Sleep s = found.get();
        return new SleepReply(
                today.toString(),
                zone.getId(),
                date.toString(),
                true,
                minute(s.start(), zone),
                minute(s.end(), zone),
                Units.hours(s.sleepSeconds()),
                s.sleepSeconds(),
                Units.hours(s.deepSeconds()),
                s.deepSeconds(),
                Units.hours(s.lightSeconds()),
                s.lightSeconds(),
                Units.hours(s.remSeconds()),
                s.remSeconds(),
                Units.hours(s.awakeSeconds()),
                s.awakeSeconds(),
                s.score(),
                Units.label(s.quality()),
                oneDecimal(s.averageRespirationBreathsPerMinute()),
                oneDecimal(s.averageSpo2Percent()));
    }

    // Units

    static Double km(Double metres) {
        return metres == null ? null : Math.round(metres / 10) / 100.0;
    }

    private static Long seconds(Double seconds) {
        return whole(seconds);
    }

    private static Long whole(Double value) {
        return value == null ? null : Math.round(value);
    }

    private static Double oneDecimal(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }

    private static String minute(LocalDateTime time) {
        return time == null ? null : MINUTE.format(time);
    }

    private static String minute(Instant instant, ZoneId zone) {
        return instant == null ? null : MINUTE.format(instant.atZone(zone));
    }
}
