package com.hammez.stride.garmin;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything Garmin has for one day of the health log (phase 4b), from {@link GarminClient#dailyLog}. A part Garmin
 * doesn't have is null, or an empty list for activities.
 *
 * @param isFinal       false for today (or later): the watch and scale may still sync
 * @param sleep         the night that ended on the morning of {@code date}, as in {@link GarminClient#sleep}
 * @param steps         the day's steps
 * @param caloriesEaten the food logged for the day, in kcal. Not calories burned. See {@link DayTotals}.
 * @param activities    the ones that started that day in the owner's time zone, oldest first
 * @param weight        the day's weight and body composition, picked as {@link DayWeight} does
 */
public record LogDay(
        LocalDate date,
        boolean isFinal,
        Sleep sleep,
        Integer steps,
        Integer caloriesEaten,
        List<Activity> activities,
        DayWeight weight) {

    public LogDay {
        activities = List.copyOf(activities);
    }

    /**
     * One of the day's activities and its group.
     *
     * @param group Garmin's top-level type ({@link ActivityTypes}), or null if the type isn't in Garmin's list
     */
    public record Activity(ActivitySummary activity, String group) {
    }
}
