package com.hammez.stride.mcp;

/**
 * What {@code get_sleep} returns, as JSON. Built by {@link ToolReplies}. A null value is left out of the JSON.
 * Durations are always h:mm:ss, so 8 minutes awake can't be read as 8 hours.
 *
 * @param date     the morning the night ended
 * @param recorded false if no sleep was recorded that night (then only the dates are given)
 * @param start    local time in the owner's time zone: {@code 2026-10-22T22:37}
 * @param asleep   deep + light + REM, not counting time awake
 * @param score    0 to 100
 * @param quality  Garmin's label for the score: Excellent, Good, Fair, Poor
 */
record SleepReply(
        String today,
        String timeZone,
        String date,
        boolean recorded,
        String start,
        String end,
        String asleep,
        Integer asleepSeconds,
        String deepSleep,
        Integer deepSleepSeconds,
        String lightSleep,
        Integer lightSleepSeconds,
        String remSleep,
        Integer remSleepSeconds,
        String awake,
        Integer awakeSeconds,
        Integer score,
        String quality,
        Double averageBreathsPerMinute,
        Double averageSpo2Percent) {

    /** A night with no sleep recorded: only the dates. */
    static SleepReply notRecorded(String today, String timeZone, String date) {
        return new SleepReply(today, timeZone, date, false,
                null, null, // start, end
                null, null, null, null, null, null, null, null, null, null, // the durations, each as text and seconds
                null, null, null, null); // score, quality, breathing, SpO2
    }
}
