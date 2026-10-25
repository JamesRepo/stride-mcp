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
 * @param muscleMassKilograms skeletal muscle mass. Garmin sends a mass, not a percentage (python-garminconnect,
 *                            October 2026; not yet seen in a real weigh-in).
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
}
