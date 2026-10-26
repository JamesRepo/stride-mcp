package com.hammez.stride.mcp;

import java.util.List;

/**
 * What {@code get_activities} returns, as JSON. Built by {@link ToolReplies}. A null value is left out of the JSON.
 *
 * @param today    today's date in the owner's time zone
 * @param timeZone the owner's Garmin profile time zone, such as {@code Europe/London}
 * @param from     first day searched, included
 * @param to       last day searched, included
 * @param type     the type asked for, or null for all types
 */
record ActivitiesReply(
        String today,
        String timeZone,
        String from,
        String to,
        String type,
        int count,
        List<Activity> activities) {

    /**
     * One activity in the list.
     *
     * @param start local time where it happened: {@code 2026-10-22T07:06}
     * @param pace  average pace: {@code 7:08 /km}
     */
    record Activity(
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
            Long elevationGainMetres,
            Integer averageHeartRateBpm,
            Integer maxHeartRateBpm,
            Integer kilocalories) {
    }
}
