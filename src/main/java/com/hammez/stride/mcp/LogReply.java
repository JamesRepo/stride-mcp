package com.hammez.stride.mcp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What {@code get_daily_log} returns, as JSON. Built by {@link ToolReplies} from {@code garmin.LogDay}s, in Garmin's
 * words, not the owner's spreadsheet's (docs/phases/phase-4b-daily-log.md). A null value is left out of the JSON, so
 * a day Garmin has nothing for is only its date and {@code final}.
 *
 * @param from first day asked for, included
 * @param to   last day asked for, included
 * @param days every day from {@code from} to {@code to}, oldest first
 */
record LogReply(
        String today,
        String timeZone,
        String from,
        String to,
        List<Day> days) {

    /**
     * One day.
     *
     * @param isFinal       {@code final} in the JSON (a Java keyword): false for today, which may still change
     * @param sleep         the night that ended that morning
     * @param caloriesEaten the food logged, in kcal. Never calories burned.
     * @param activities    the ones that started that day, oldest first; null (left out) if none
     */
    record Day(
            String date,
            @JsonProperty("final") boolean isFinal,
            Sleep sleep,
            Integer steps,
            Integer caloriesEaten,
            List<Activity> activities,
            Weight weight) {
    }

    /** @param hours asleep, to 1 decimal place */
    record Sleep(Integer score, Double hours) {
    }

    /**
     * @param type    Garmin's type key, such as {@code treadmill_running}
     * @param group   Garmin's top-level type, such as {@code running}; left out if the type isn't in Garmin's list
     * @param minutes the whole duration, not the moving time, to 1 decimal place
     */
    record Activity(long id, String name, String type, String group, Double minutes) {
    }

    /**
     * The day's first weight and first scale body composition, as in {@code get_weight} ({@code garmin.DayWeight}).
     *
     * @param time     of the weigh-in that gave the weight (or the body composition, on a day with no weight), local:
     *                 {@code 07:37}
     * @param weighIns how many weigh-ins the day had
     */
    record Weight(Double kg, Double bodyFatPercent, Double muscleMassKg, String time, int weighIns) {
    }
}
