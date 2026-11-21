package com.hammez.stride.garmin;

import java.util.List;

/**
 * Weight over a range of days: each day's {@link DayWeight}, and the numbers an AI app would otherwise have to add
 * up from a long list. Shared by {@code get_weight} and the CLI's {@code weight}, so both give the same numbers.
 * <p>
 * {@code first}, {@code last}, {@code lowest}, {@code highest} and the change use only days with a weight. A day
 * with only body composition is still in {@link #days}.
 *
 * @param days    each day with a weigh-in, oldest first
 * @param first   the oldest day with a weight, or null if no day had one
 * @param last    the newest day with a weight, or null
 * @param lowest  the day with the lowest weight, or null. Weights are compared as shown, to 1 decimal place, and a
 *                tie gives the earliest day.
 * @param highest the day with the highest weight, or null, compared in the same way
 */
public record WeightHistory(List<DayWeight> days, DayWeight first, DayWeight last, DayWeight lowest,
        DayWeight highest) {

    public WeightHistory {
        days = List.copyOf(days);
    }

    /**
     * @param weighIns a range, oldest first, as
     *                 {@link GarminClient#weighIns(java.time.LocalDate, java.time.LocalDate)} returns it
     */
    public static WeightHistory of(List<WeighIn> weighIns) {
        List<DayWeight> days = DayWeight.byDay(weighIns);
        DayWeight first = null;
        DayWeight last = null;
        DayWeight lowest = null;
        DayWeight highest = null;
        for (DayWeight day : days) {
            if (day.weight() == null) {
                continue;
            }
            if (first == null) {
                first = day;
            }
            last = day;
            // As shown, so two weights that show the same are a tie, and the earlier day wins.
            if (lowest == null || shown(day) < shown(lowest)) {
                lowest = day;
            }
            if (highest == null || shown(day) > shown(highest)) {
                highest = day;
            }
        }
        return new WeightHistory(days, first, last, lowest, highest);
    }

    /** The day's weight to 1 decimal place, as the CLI and the tools show it. */
    private static double shown(DayWeight day) {
        return Units.oneDecimal(day.weight().weightKilograms());
    }

    /**
     * The last weight minus the first, in kg to 1 decimal place. Worked out from the weights rounded to 1 decimal
     * place, as they are shown, so it always matches them. Null if no day had a weight.
     */
    public Double changeKilograms() {
        if (first == null) {
            return null;
        }
        return Units.oneDecimal(Units.oneDecimal(last.weight().weightKilograms())
                - Units.oneDecimal(first.weight().weightKilograms()));
    }
}
