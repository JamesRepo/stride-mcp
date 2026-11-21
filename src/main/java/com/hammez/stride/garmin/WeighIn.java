package com.hammez.stride.garmin;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One weigh-in, from a scale or typed in. A value Garmin leaves out is null: a plain scale sends only the weight.
 *
 * @param date                the day it was recorded
 * @param time                when it was recorded
 * @param bodyFatPercent      0–100
 * @param bodyWaterPercent    0–100
 * @param muscleMassKilograms skeletal muscle mass. Garmin sends a mass in grams, not a percentage (seen in a real
 *                            weigh-in from an Index scale, 20 November 2026).
 * @param visceralFat         Garmin's visceral fat rating (from scales that measure it), not a mass
 * @param metabolicAgeYears   from scales that estimate it
 */
public record WeighIn(
        LocalDate date,
        Instant time,
        Double weightKilograms,
        Double bmi,
        Double bodyFatPercent,
        Double bodyWaterPercent,
        Double muscleMassKilograms,
        Double boneMassKilograms,
        Integer visceralFat,
        Integer metabolicAgeYears) {

    /**
     * True if it has a value only a scale measures: body fat, body water, muscle or bone mass, visceral fat or
     * metabolic age. Not the BMI: Garmin works that out for a weight typed in on Garmin Connect too (seen in the
     * capture, 20 November 2026).
     */
    public boolean hasScaleBodyComposition() {
        return bodyFatPercent != null || bodyWaterPercent != null || muscleMassKilograms != null
                || boneMassKilograms != null || visceralFat != null || metabolicAgeYears != null;
    }
}
