package com.hammez.stride.garmin;

import java.util.List;

/**
 * One activity in full: its summary, a few extra totals, and each lap. A value Garmin leaves out is null.
 *
 * @param aerobicTrainingEffect   0.0–5.0
 * @param anaerobicTrainingEffect 0.0–5.0
 * @param laps                    in order, empty if the activity has none
 */
public record ActivityDetails(
        ActivitySummary summary,
        Double maxSpeedMetresPerSecond,
        Double averageCadenceStepsPerMinute,
        Integer averagePowerWatts,
        Double aerobicTrainingEffect,
        Double anaerobicTrainingEffect,
        Double trainingLoad,
        List<Lap> laps) {

    public ActivityDetails {
        laps = List.copyOf(laps);
    }
}
