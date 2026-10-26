package com.hammez.stride.mcp;

import java.util.List;

/**
 * What {@code get_activity} returns, as JSON. Built by {@link ToolReplies}. A null value is left out of the JSON.
 *
 * @param start                   local time where it happened: {@code 2026-10-22T07:06}
 * @param fastestPace             from the maximum speed: {@code 4:30 /km}
 * @param aerobicTrainingEffect   0.0 to 5.0
 * @param anaerobicTrainingEffect 0.0 to 5.0
 * @param laps                    in order, empty if there are none
 */
record ActivityReply(
        String today,
        String timeZone,
        long id,
        String name,
        String type,
        String start,
        Double distanceKm,
        String duration,
        Long durationSeconds,
        String movingDuration,
        Long movingDurationSeconds,
        String pace,
        String fastestPace,
        Long elevationGainMetres,
        Integer averageHeartRateBpm,
        Integer maxHeartRateBpm,
        Long averageCadenceStepsPerMinute,
        Integer averagePowerWatts,
        Integer kilocalories,
        Double aerobicTrainingEffect,
        Double anaerobicTrainingEffect,
        Long trainingLoad,
        List<Lap> laps) {

    /** One lap. {@code number} counts from 1. */
    record Lap(
            int number,
            Double distanceKm,
            String duration,
            Long durationSeconds,
            String pace,
            Integer averageHeartRateBpm,
            Integer maxHeartRateBpm,
            Long elevationGainMetres) {
    }
}
