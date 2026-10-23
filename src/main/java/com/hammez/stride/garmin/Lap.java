package com.hammez.stride.garmin;

/**
 * One lap of an activity. Metres and seconds. A value Garmin leaves out is null.
 *
 * @param number 1, 2, 3…
 */
public record Lap(
        int number,
        Double distanceMetres,
        Double durationSeconds,
        Double averageSpeedMetresPerSecond,
        Integer averageHeartRateBpm,
        Integer maxHeartRateBpm,
        Double elevationGainMetres) {
}
