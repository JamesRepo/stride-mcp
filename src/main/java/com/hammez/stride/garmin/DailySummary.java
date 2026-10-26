package com.hammez.stride.garmin;

import java.time.LocalDate;

/**
 * One day's totals. Every value is null for a day with no data.
 *
 * @param averageStressLevel 0–100
 * @param bodyBatteryHighest 0–100
 * @param bodyBatteryLowest  0–100
 * @param floorsClimbed      Garmin counts part floors, so not a whole number
 */
public record DailySummary(
        LocalDate date,
        Integer steps,
        Integer stepGoal,
        Double distanceMetres,
        Integer totalKilocalories,
        Integer activeKilocalories,
        Integer restingHeartRateBpm,
        Integer minHeartRateBpm,
        Integer maxHeartRateBpm,
        Integer averageStressLevel,
        Integer bodyBatteryHighest,
        Integer bodyBatteryLowest,
        Integer moderateIntensityMinutes,
        Integer vigorousIntensityMinutes,
        Double floorsClimbed) {

    /** True for a day with no data: every value is null. Decides the cache lifetime and the tools' "recorded". */
    public boolean isEmpty() {
        return steps == null && stepGoal == null && distanceMetres == null && totalKilocalories == null
                && activeKilocalories == null && restingHeartRateBpm == null && minHeartRateBpm == null
                && maxHeartRateBpm == null && averageStressLevel == null && bodyBatteryHighest == null
                && bodyBatteryLowest == null && moderateIntensityMinutes == null && vigorousIntensityMinutes == null
                && floorsClimbed == null;
    }
}
