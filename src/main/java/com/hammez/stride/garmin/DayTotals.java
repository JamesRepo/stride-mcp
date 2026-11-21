package com.hammez.stride.garmin;

import java.time.LocalDate;

/**
 * One day's steps, and the calories eaten that day, from the range of daily summaries (phase 4b). A value Garmin
 * leaves out is null.
 *
 * @param steps         the day's steps ({@code movement.steps.value})
 * @param caloriesEaten the food logged for the day, in kcal ({@code calories.consumedValue}): MyFitnessPal's daily
 *                      total (checked 21 November 2026). Never calories burned. Null if none was logged, which
 *                      Garmin sends as missing, null or 0.
 */
public record DayTotals(LocalDate date, Integer steps, Integer caloriesEaten) {
}
