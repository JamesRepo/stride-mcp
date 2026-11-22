package com.hammez.stride.garmin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything Garmin has for one day of the health log (phase 4b), from {@link GarminClient#dailyLog}. A part Garmin
 * doesn't have is null, or an empty list for activities.
 *
 * @param state          whether the day can still change, decided by {@link GarminClient#dailyLog}, so everything
 *                       showing it agrees on what "today" was
 * @param watchDataUntil where the day's watch data ends, on the clock where the watch was, or null if Garmin has
 *                       none ({@link DayTotals#hasWatchData()})
 * @param sleep          the night that ended on the morning of {@code date}, as in {@link GarminClient#sleep}
 * @param steps          the day's steps
 * @param caloriesEaten  the food logged for the day, in kcal. Not calories burned. See {@link DayTotals}.
 * @param activities     the ones that started that day in the owner's time zone, oldest first
 * @param weight         the day's weight and body composition, picked as {@link DayWeight} does
 */
public record LogDay(
        LocalDate date,
        State state,
        LocalDateTime watchDataUntil,
        Sleep sleep,
        Integer steps,
        Integer caloriesEaten,
        List<Activity> activities,
        DayWeight weight) {

    public LogDay {
        activities = List.copyOf(activities);
    }

    /** Whether a day of the log can still change. */
    public enum State {
        /** Before today, and the watch has synced since it ended: it won't change. */
        FINAL,
        /** Today (or later): the watch and scale may still sync. */
        TODAY,
        /** Before today, but the watch hasn't synced since it ended, as far as Garmin's replies show. */
        WATCH_NOT_SYNCED
    }

    /** False if the day may still change: {@link State#TODAY} or {@link State#WATCH_NOT_SYNCED}. */
    public boolean isFinal() {
        return state == State.FINAL;
    }

    /**
     * One of the day's activities and its group.
     *
     * @param group Garmin's top-level type ({@link ActivityTypes}), or null if the type isn't in Garmin's list
     */
    public record Activity(ActivitySummary activity, String group) {
    }
}
