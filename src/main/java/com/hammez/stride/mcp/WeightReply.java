package com.hammez.stride.mcp;

import java.util.List;

/**
 * What {@code get_weight} returns, as JSON. Built by {@link ToolReplies} from a {@code garmin.WeightHistory}.
 * A null value is left out of the JSON.
 *
 * @param from    first day asked for, included
 * @param to      last day asked for, included
 * @param summary worked out for the AI, which is bad at adding up long lists
 * @param days    each day with a weigh-in, oldest first
 */
record WeightReply(
        String today,
        String timeZone,
        String from,
        String to,
        Summary summary,
        List<Day> days) {

    /**
     * {@code first}, {@code last}, {@code changeKg}, {@code lowest} and {@code highest} use only days with a
     * weight, and are left out if there are none. {@code daysWithWeighIns} counts every day listed.
     *
     * @param changeKg last minus first, from the weights as shown (1 decimal place)
     * @param lowest   compared as shown; the earliest day if there's a tie
     * @param highest  compared as shown; the earliest day if there's a tie
     */
    record Summary(int daysWithWeighIns, Point first, Point last, Double changeKg, Point lowest, Point highest) {
    }

    record Point(String date, Double weightKg) {
    }

    /**
     * One day: its first weight, its first scale body composition, and its BMI (see {@code garmin.DayWeight}).
     *
     * @param time                              of the weigh-in that gave the weight (or the body composition,
     *                                          on a day with no weight), local: {@code 07:37}
     * @param visceralFat                       Garmin's rating, not a mass
     * @param weighIns                          how many weigh-ins the day had
     * @param bodyCompositionFromAnotherWeighIn true if the body composition isn't from the weigh-in that gave the
     *                                          weight; null (left out) otherwise
     */
    record Day(
            String date,
            String time,
            Double weightKg,
            Double bodyFatPercent,
            Double bodyWaterPercent,
            Double muscleMassKg,
            Double boneMassKg,
            Double bmi,
            Integer visceralFat,
            Integer metabolicAge,
            int weighIns,
            Boolean bodyCompositionFromAnotherWeighIn) {
    }
}
