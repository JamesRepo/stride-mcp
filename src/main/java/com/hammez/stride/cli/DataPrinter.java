package com.hammez.stride.cli;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.hammez.stride.garmin.ActivityDetails;
import com.hammez.stride.garmin.ActivitySummary;
import com.hammez.stride.garmin.DailySummary;
import com.hammez.stride.garmin.Lap;
import com.hammez.stride.garmin.Sleep;
import com.hammez.stride.garmin.WeighIn;

/**
 * Prints Garmin data for a terminal, in metric units: km with 2 decimals, h:mm:ss or m:ss, min:ss /km.
 * A value Garmin left out is "–" in a table, and its line is left out of a list. Never "null".
 * <p>
 * Garmin sends 0 for the distance, speed and moving time of activities without them (strength training, indoor
 * cycling), so for activities a 0 there is shown as "–" too.
 */
final class DataPrinter {

    static final String NONE = "–";

    // English whatever the computer's language, so the output is the same everywhere.
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);
    private static final int LABEL_WIDTH = 17;

    private final PrintStream out;

    DataPrinter(PrintStream out) {
        this.out = out;
    }

    /** A table of activities, newest first, then how many. {@code to} is today. */
    void activities(List<ActivitySummary> activities, LocalDate from, LocalDate to, String type) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        String period = days == 1 ? "today (" + DAY.format(to) + ")"
                : "in the last " + days + " days (" + DAY.format(from) + " to " + DAY.format(to) + ")";
        String kind = type == null ? "" : type + " ";
        if (activities.isEmpty()) {
            out.println("No " + kind + "activities " + period + ".");
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (ActivitySummary a : activities) {
            rows.add(List.of(
                    a.startLocal() == null ? NONE : DAY.format(a.startLocal()),
                    orNone(a.type()),
                    orNone(a.name()),
                    km(positive(a.distanceMetres())),
                    duration(a.durationSeconds()),
                    pace(a.averageSpeedMetresPerSecond()),
                    orNone(a.averageHeartRateBpm()),
                    String.valueOf(a.id())));
        }
        table(List.of("Date", "Type", "Name", "Distance", "Time", "Pace", "Avg HR", "Id"), rows, Set.of(3));
        out.println(activities.size() + " " + kind + (activities.size() == 1 ? "activity" : "activities")
                + " " + period + ".");
    }

    /** One activity: a list of its totals, then a table of its laps. */
    void activity(ActivityDetails details) {
        ActivitySummary a = details.summary();
        out.println(a.name() == null ? "Activity " + a.id() : a.name());
        var lines = new Lines();
        lines.add("Start", a.startLocal() == null ? null : DATE_TIME.format(a.startLocal()));
        lines.add("Type", a.type());
        lines.add("Id", String.valueOf(a.id()));
        lines.add("Distance", positive(a.distanceMetres()) == null ? null : km(a.distanceMetres()));
        Double moving = positive(a.movingDurationSeconds());
        lines.add("Time", a.durationSeconds() == null ? null
                : duration(a.durationSeconds()) + (moving == null ? "" : " (moving " + duration(moving) + ")"));
        lines.add("Pace", joined(", ",
                positive(a.averageSpeedMetresPerSecond()) == null ? null : pace(a.averageSpeedMetresPerSecond()),
                positive(details.maxSpeedMetresPerSecond()) == null ? null
                        : "fastest " + pace(details.maxSpeedMetresPerSecond())));
        lines.add("Elevation gain", a.elevationGainMetres() == null ? null : metres(a.elevationGainMetres()));
        lines.add("Heart rate", heartRate(
                a.averageHeartRateBpm() == null ? null : "avg " + a.averageHeartRateBpm(),
                a.maxHeartRateBpm() == null ? null : "max " + a.maxHeartRateBpm()));
        lines.add("Cadence", details.averageCadenceStepsPerMinute() == null ? null
                : format("%.0f steps/min", details.averageCadenceStepsPerMinute()));
        lines.add("Power", details.averagePowerWatts() == null ? null : details.averagePowerWatts() + " W");
        lines.add("Calories", a.kilocalories() == null ? null : format("%,d kcal", a.kilocalories()));
        lines.add("Training effect", joined(", ",
                details.aerobicTrainingEffect() == null ? null
                        : format("%.1f aerobic", details.aerobicTrainingEffect()),
                details.anaerobicTrainingEffect() == null ? null
                        : format("%.1f anaerobic", details.anaerobicTrainingEffect())));
        lines.add("Training load", details.trainingLoad() == null ? null : format("%.0f", details.trainingLoad()));
        lines.print();

        out.println();
        if (details.laps().isEmpty()) {
            out.println("No laps.");
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (Lap lap : details.laps()) {
            rows.add(List.of(
                    String.valueOf(lap.number()),
                    km(positive(lap.distanceMetres())),
                    duration(lap.durationSeconds()),
                    pace(lap.averageSpeedMetresPerSecond()),
                    orNone(lap.averageHeartRateBpm()),
                    orNone(lap.maxHeartRateBpm()),
                    lap.elevationGainMetres() == null ? NONE : metres(lap.elevationGainMetres())));
        }
        table(List.of("Lap", "Distance", "Time", "Pace", "Avg HR", "Max HR", "Elevation"), rows, Set.of(1));
    }

    /**
     * One day's totals, the night that ended that morning, and the day's latest weight and body composition.
     * Or a line saying there is none of these.
     *
     * @param weighIns oldest first
     */
    void summary(DailySummary day, Optional<Sleep> sleep, List<WeighIn> weighIns) {
        var lines = new Lines();
        lines.add("Steps", day.steps() == null ? null
                : format("%,d", day.steps()) + (day.stepGoal() == null ? "" : format(" (goal %,d)", day.stepGoal())));
        lines.add("Distance", day.distanceMetres() == null ? null : km(day.distanceMetres()));
        lines.add("Calories", day.totalKilocalories() != null
                ? format("%,d kcal", day.totalKilocalories())
                        + (day.activeKilocalories() == null ? "" : format(" (%,d active)", day.activeKilocalories()))
                : day.activeKilocalories() == null ? null : format("%,d active kcal", day.activeKilocalories()));
        lines.add("Heart rate", heartRate(
                day.restingHeartRateBpm() == null ? null : "resting " + day.restingHeartRateBpm(),
                day.minHeartRateBpm() == null ? null : "min " + day.minHeartRateBpm(),
                day.maxHeartRateBpm() == null ? null : "max " + day.maxHeartRateBpm()));
        lines.add("Stress", day.averageStressLevel() == null ? null
                : "average " + day.averageStressLevel() + " (0–100)");
        lines.add("Body Battery", joined(", ",
                day.bodyBatteryLowest() == null ? null : "lowest " + day.bodyBatteryLowest(),
                day.bodyBatteryHighest() == null ? null : "highest " + day.bodyBatteryHighest()));
        lines.add("Intensity", joined(", ",
                day.moderateIntensityMinutes() == null ? null : day.moderateIntensityMinutes() + " min moderate",
                day.vigorousIntensityMinutes() == null ? null : day.vigorousIntensityMinutes() + " min vigorous"));
        // Garmin counts part floors. Only whole ones were climbed.
        lines.add("Floors climbed", day.floorsClimbed() == null ? null
                : String.valueOf((long) Math.floor(day.floorsClimbed())));
        sleep.ifPresent(night -> lines.add("Sleep", joined(", ",
                hours(night.sleepSeconds()),
                score(night) == null ? null : "score " + score(night))));
        weighIns(lines, weighIns);
        if (lines.isEmpty()) {
            out.println("No data recorded for " + DATE.format(day.date()) + ".");
            return;
        }
        out.println(DATE.format(day.date()));
        lines.print();
    }

    /**
     * The night that ended on the morning of {@code date}, or a line saying none was recorded. Durations are always
     * h:mm:ss here, so 8 minutes awake can't be read as 8 hours.
     *
     * @param zone for the start and end times: the Garmin profile's
     */
    void sleep(LocalDate date, Optional<Sleep> found, ZoneId zone) {
        if (found.isEmpty()) {
            out.println("No sleep recorded for the night ending " + DAY.format(date) + ".");
            return;
        }
        Sleep sleep = found.get();
        out.println("Night ending " + DATE.format(date));
        var lines = new Lines();
        lines.add("Start", time(sleep.start(), zone));
        lines.add("End", time(sleep.end(), zone));
        lines.add("Asleep", hours(sleep.sleepSeconds()));
        lines.add("  Deep", hours(sleep.deepSeconds()));
        lines.add("  Light", hours(sleep.lightSeconds()));
        lines.add("  REM", hours(sleep.remSeconds()));
        lines.add("Awake", hours(sleep.awakeSeconds()));
        lines.add("Score", score(sleep));
        lines.add("Respiration", sleep.averageRespirationBreathsPerMinute() == null ? null
                : format("%.0f breaths/min", sleep.averageRespirationBreathsPerMinute()));
        lines.add("SpO2", sleep.averageSpo2Percent() == null ? null
                : format("%.0f%% average", sleep.averageSpo2Percent()));
        lines.print();
    }

    /** "91 (Excellent)", or just one of them. */
    private static String score(Sleep sleep) {
        return sleep.score() == null ? label(sleep.quality())
                : sleep.score() + (sleep.quality() == null ? "" : " (" + label(sleep.quality()) + ")");
    }

    /**
     * The day's latest weight, with the latest body composition under it. These can come from different weigh-ins:
     * a scale in the morning (with body fat and so on) and a weight typed in later. If so, the weight line says so.
     * Garmin gives muscle mass as a mass, so its share of the weight is worked out, from the same weigh-in.
     *
     * @param weighIns oldest first
     */
    private static void weighIns(Lines lines, List<WeighIn> weighIns) {
        WeighIn weight = null;
        WeighIn body = null;
        for (WeighIn w : weighIns) {
            if (w.weightKilograms() != null) {
                weight = w;
            }
            if (hasBodyComposition(w)) {
                body = w;
            }
        }
        if (weight == null && body == null) {
            return;
        }
        String notes = joined("; ",
                weighIns.size() == 1 ? null : "latest of " + weighIns.size() + " weigh-ins",
                weight == null || body == null || body == weight ? null : "body composition from another weigh-in");
        lines.add("Weight", (weight == null ? "not recorded" : kilograms(weight.weightKilograms()))
                + (notes == null ? "" : " (" + notes + ")"));
        if (body != null) {
            bodyComposition(lines, body);
        }
    }

    private static boolean hasBodyComposition(WeighIn w) {
        return w.bmi() != null || w.bodyFatPercent() != null || w.bodyWaterPercent() != null
                || w.muscleMassKilograms() != null || w.boneMassKilograms() != null || w.visceralFat() != null
                || w.metabolicAgeYears() != null;
    }

    private static void bodyComposition(Lines lines, WeighIn w) {
        lines.add("  BMI", w.bmi() == null ? null : format("%.1f", w.bmi()));
        lines.add("  Body fat", w.bodyFatPercent() == null ? null : format("%.1f%%", w.bodyFatPercent()));
        lines.add("  Body water", w.bodyWaterPercent() == null ? null : format("%.1f%%", w.bodyWaterPercent()));
        lines.add("  Muscle mass", w.muscleMassKilograms() == null ? null
                : kilograms(w.muscleMassKilograms()) + (positive(w.weightKilograms()) == null ? ""
                        : format(" (%.0f%% of weight)", 100 * w.muscleMassKilograms() / w.weightKilograms())));
        lines.add("  Bone mass", w.boneMassKilograms() == null ? null : kilograms(w.boneMassKilograms()));
        lines.add("  Visceral fat", w.visceralFat() == null ? null : String.valueOf(w.visceralFat()));
        lines.add("  Metabolic age", w.metabolicAgeYears() == null ? null : w.metabolicAgeYears() + " years");
    }

    /** "Label  value" lines, lined up. A null value leaves its line out. */
    private final class Lines {

        private final List<String> lines = new ArrayList<>();

        void add(String label, String value) {
            if (value != null) {
                lines.add(String.format("%-" + LABEL_WIDTH + "s%s", label, value));
            }
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }

        void print() {
            lines.forEach(out::println);
        }
    }

    /** Columns two spaces apart, each as wide as its widest cell. No spaces at the end of a line. */
    private void table(List<String> headers, List<List<String>> rows, Set<Integer> rightAligned) {
        int[] widths = new int[headers.size()];
        for (int c = 0; c < widths.length; c++) {
            widths[c] = headers.get(c).length();
            for (List<String> row : rows) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        var all = new ArrayList<List<String>>();
        all.add(headers);
        all.addAll(rows);
        for (List<String> row : all) {
            var line = new StringBuilder();
            for (int c = 0; c < widths.length; c++) {
                String pad = " ".repeat(widths[c] - row.get(c).length());
                line.append(c == 0 ? "" : "  ").append(rightAligned.contains(c) ? pad + row.get(c) : row.get(c) + pad);
            }
            out.println(line.toString().stripTrailing());
        }
    }

    // Units

    static String km(Double metres) {
        return metres == null ? NONE : format("%.2f km", metres / 1000);
    }

    static String metres(double metres) {
        return format("%.0f m", metres);
    }

    static String kilograms(double kilograms) {
        return format("%.1f kg", kilograms);
    }

    /** h:mm:ss, or m:ss under an hour. */
    static String duration(Double seconds) {
        if (seconds == null) {
            return NONE;
        }
        long total = Math.round(seconds);
        long h = total / 3600;
        long m = total % 3600 / 60;
        long s = total % 60;
        return h > 0 ? format("%d:%02d:%02d", h, m, s) : format("%d:%02d", m, s);
    }

    /** Always h:mm:ss, or null. */
    static String hours(Integer seconds) {
        if (seconds == null) {
            return null;
        }
        return format("%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    /** min:ss per km. "–" for no speed. */
    static String pace(Double metresPerSecond) {
        if (metresPerSecond == null || metresPerSecond <= 0) {
            return NONE;
        }
        long secondsPerKm = Math.round(1000 / metresPerSecond);
        return format("%d:%02d /km", secondsPerKm / 60, secondsPerKm % 60);
    }

    /** Null for 0 or less: Garmin's way of saying "doesn't apply" for an activity's distance, speed or time. */
    private static Double positive(Double value) {
        return value == null || value <= 0 ? null : value;
    }

    private static String heartRate(String... parts) {
        String joined = joined(", ", parts);
        return joined == null ? null : joined + " bpm";
    }

    /** The parts that aren't null, or null if they all are. */
    private static String joined(String separator, String... parts) {
        var present = new ArrayList<String>();
        for (String part : parts) {
            if (part != null) {
                present.add(part);
            }
        }
        return present.isEmpty() ? null : String.join(separator, present);
    }

    private static String time(Instant instant, ZoneId zone) {
        return instant == null ? null : DAY_TIME.format(instant.atZone(zone));
    }

    /** Garmin's {@code EXCELLENT} as "Excellent". */
    private static String label(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String words = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String orNone(Object value) {
        return value == null ? NONE : value.toString();
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ENGLISH, pattern, args);
    }
}
