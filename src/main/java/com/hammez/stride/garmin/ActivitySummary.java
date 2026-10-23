package com.hammez.stride.garmin;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One activity, as it appears in a list. Metres and seconds throughout. A value Garmin leaves out is null, never 0.
 * Garmin does send {@code 0.0} for the distance and speed of activities without them, such as strength training.
 *
 * @param type       Garmin's type key: {@code running}, {@code trail_running}, {@code strength_training}…
 * @param startLocal the wall-clock time where the activity happened
 * @param start      the exact moment it started
 */
public record ActivitySummary(
        long id,
        String name,
        String type,
        LocalDateTime startLocal,
        Instant start,
        Double distanceMetres,
        Double durationSeconds,
        Double movingDurationSeconds,
        Double elevationGainMetres,
        Double averageSpeedMetresPerSecond,
        Integer averageHeartRateBpm,
        Integer maxHeartRateBpm,
        Integer kilocalories) {
}
