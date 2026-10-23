package com.hammez.stride.garmin;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One night's sleep. A value Garmin leaves out is null.
 *
 * @param date         the morning the night ended
 * @param sleepSeconds asleep, not counting awake time (deep + light + REM)
 * @param score        0–100
 * @param quality      Garmin's label for the score: {@code EXCELLENT}, {@code GOOD}, {@code FAIR}, {@code POOR}
 */
public record Sleep(
        LocalDate date,
        Instant start,
        Instant end,
        Integer sleepSeconds,
        Integer deepSeconds,
        Integer lightSeconds,
        Integer remSeconds,
        Integer awakeSeconds,
        Integer score,
        String quality,
        Double averageRespirationBreathsPerMinute,
        Double averageSpo2Percent) {
}
