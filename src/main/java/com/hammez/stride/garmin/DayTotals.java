package com.hammez.stride.garmin;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day's steps, the calories eaten that day, and how far its watch data goes, from the range of daily summaries
 * (phase 4b). A value Garmin leaves out is null.
 *
 * @param steps          the day's steps ({@code movement.steps.value})
 * @param caloriesEaten  the food logged for the day, in kcal ({@code calories.consumedValue}): MyFitnessPal's daily
 *                       total (checked 21 November 2026). Never calories burned. Null if none was logged, which
 *                       Garmin sends as missing, null or 0.
 * @param watchDataUntil where the day's watch data ends ({@code wellnessChronology.endTimestampLocal}), on the clock
 *                       where the watch was; null if Garmin has none. A day the watch synced after runs to the next
 *                       midnight (all four past days in the capture of 20 November 2026). One it hasn't stops at the
 *                       last sync: 08:42 on 20 November, a minute before the watch synced.
 */
public record DayTotals(LocalDate date, Integer steps, Integer caloriesEaten, LocalDateTime watchDataUntil) {

    /**
     * True if the day has watch data: a {@code wellnessChronology} and a steps value (0 counts). Garmin might make a
     * day's chronology when only MyFitnessPal synced, as on 21 November 2026 (calories eaten, no steps; its
     * chronology wasn't seen). Until a capture shows what Garmin does, a chronology alone doesn't count (the owner's
     * choice, 22 November 2026).
     */
    public boolean hasWatchData() {
        return watchDataUntil != null && steps != null;
    }

    /** True if the day has watch data that runs to its end, so the watch synced after the day ended. */
    public boolean watchDataToMidnight() {
        return hasWatchData() && !watchDataUntil.isBefore(date.plusDays(1).atStartOfDay());
    }
}
