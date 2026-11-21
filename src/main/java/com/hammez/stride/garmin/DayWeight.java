package com.hammez.stride.garmin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A day's weight and body composition, picked from its weigh-ins: the first weight of the day, and the first body
 * composition (the owner's choices, 20 November 2026). Morning weigh-ins are the most consistent, so a weight typed
 * in later, or one taken after a meal, doesn't move the trend. The two can come from different weigh-ins: a weight
 * typed in on Garmin Connect has only the BMI Garmin works out, so one typed in before stepping on the scale doesn't
 * lose the scale's readings. The BMI doesn't count when choosing the body composition, and comes from
 * {@link #bmi()}. Shared by the CLI and the MCP tools, so a day's weight is the same everywhere.
 *
 * @param weight          the first weigh-in with a weight, or null if none had one
 * @param bodyComposition the first weigh-in with a value only a scale measures
 *                        ({@link WeighIn#hasScaleBodyComposition()}), or null if none had one
 * @param weighIns        how many weigh-ins the day had
 */
public record DayWeight(WeighIn weight, WeighIn bodyComposition, int weighIns) {

    /**
     * @param weighIns one day's weigh-ins, oldest first, as {@link GarminClient#weighIns(LocalDate)} returns them. Not
     *                 a range: use {@link #byDay} for that.
     * @return empty if no weigh-in had a weight or any scale body composition
     */
    public static Optional<DayWeight> of(List<WeighIn> weighIns) {
        WeighIn weight = null;
        WeighIn body = null;
        for (WeighIn w : weighIns) {
            if (weight == null && w.weightKilograms() != null) {
                weight = w;
            }
            if (body == null && w.hasScaleBodyComposition()) {
                body = w;
            }
        }
        if (weight == null && body == null) {
            return Optional.empty();
        }
        return Optional.of(new DayWeight(weight, body, weighIns.size()));
    }

    /**
     * Each day's weight, oldest day first, by {@link #of} on each day's weigh-ins. Days with none, or with none that
     * had a value, are left out.
     *
     * @param weighIns a range, oldest first, as {@link GarminClient#weighIns(LocalDate, LocalDate)} returns it
     */
    public static List<DayWeight> byDay(List<WeighIn> weighIns) {
        Map<LocalDate, List<WeighIn>> days = new LinkedHashMap<>();
        for (WeighIn w : weighIns) {
            days.computeIfAbsent(w.date(), day -> new ArrayList<>()).add(w);
        }
        var found = new ArrayList<DayWeight>();
        for (List<WeighIn> day : days.values()) {
            of(day).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /**
     * The day's BMI: from the body composition's weigh-in if it has one, otherwise from the weight's. So a day with
     * only a weight typed in still has the BMI Garmin worked out for it.
     */
    public Double bmi() {
        if (bodyComposition != null && bodyComposition.bmi() != null) {
            return bodyComposition.bmi();
        }
        return weight == null ? null : weight.bmi();
    }

    /** The day these weigh-ins are from. */
    public LocalDate date() {
        return picked().date();
    }

    /** The weigh-in that gave the weight, or the body composition if no weigh-in had a weight. */
    public WeighIn picked() {
        return weight != null ? weight : bodyComposition;
    }

    /** True if the weight and the body composition come from two different weigh-ins. */
    public boolean fromDifferentWeighIns() {
        return weight != null && bodyComposition != null && weight != bodyComposition;
    }
}
