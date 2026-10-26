package com.hammez.stride.mcp;

/**
 * What {@code get_daily_summary} returns, as JSON. Built by {@link ToolReplies}. A null value is left out of the JSON.
 *
 * @param recorded           false if Garmin has nothing for the day (then only the dates are given)
 * @param averageStressLevel 0 to 100
 * @param bodyBatteryLowest  0 to 100
 * @param bodyBatteryHighest 0 to 100
 * @param floorsClimbed      whole floors (Garmin counts part floors)
 * @param weight             null if there was no weigh-in that day
 * @param weightLeftOut      why the weight is missing when Garmin's reply for it couldn't be read
 */
record DayReply(
        String today,
        String timeZone,
        String date,
        boolean recorded,
        Integer steps,
        Integer stepGoal,
        Double distanceKm,
        Integer totalKilocalories,
        Integer activeKilocalories,
        Integer restingHeartRateBpm,
        Integer minHeartRateBpm,
        Integer maxHeartRateBpm,
        Integer averageStressLevel,
        Integer bodyBatteryLowest,
        Integer bodyBatteryHighest,
        Integer moderateIntensityMinutes,
        Integer vigorousIntensityMinutes,
        Long floorsClimbed,
        Weight weight,
        String weightLeftOut) {

    /** A day Garmin has nothing for: only the dates, and why the weight is missing if it couldn't be read. */
    static DayReply notRecorded(String today, String timeZone, String date, String weightLeftOut) {
        return new DayReply(today, timeZone, date, false,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, // the totals
                null, weightLeftOut);
    }

    /**
     * The day's latest weight, and the latest body composition (see {@code garmin.DayWeight}).
     *
     * @param weighIns                          how many weigh-ins the day had
     * @param bodyCompositionFromAnotherWeighIn true if the body composition isn't from the weigh-in that gave the
     *                                          weight; null (left out) otherwise
     * @param visceralFat                       Garmin's rating, not a mass
     */
    record Weight(
            Double kilograms,
            int weighIns,
            Boolean bodyCompositionFromAnotherWeighIn,
            Double bmi,
            Double bodyFatPercent,
            Double bodyWaterPercent,
            Double muscleMassKilograms,
            Double boneMassKilograms,
            Integer visceralFat,
            Integer metabolicAgeYears) {
    }
}
