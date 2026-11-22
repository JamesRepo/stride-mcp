package com.hammez.stride.cli;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.hammez.stride.garmin.ActivityDetails;
import com.hammez.stride.garmin.ActivitySummary;
import com.hammez.stride.garmin.DailySummary;
import com.hammez.stride.garmin.DayWeight;
import com.hammez.stride.garmin.Lap;
import com.hammez.stride.garmin.LogDay;
import com.hammez.stride.garmin.Sleep;
import com.hammez.stride.garmin.Units;
import com.hammez.stride.garmin.WeighIn;
import com.hammez.stride.garmin.WeightHistory;

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
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
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
     * One day's totals, the night that ended that morning, and the day's first weight and body composition.
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
     * A table of each day's weight and body composition, oldest first ({@link DayWeight}), then the summary: how
     * many days had a weigh-in, the first and last weight and the change, and the lowest and highest. A column
     * with nothing in it on any day is left out, so a plain scale shows only the weight. A day whose body
     * composition is from another weigh-in than its weight is marked with a *.
     *
     * @param zone for the times of the weigh-ins: the Garmin profile's
     */
    void weight(WeightHistory history, LocalDate from, LocalDate to, ZoneId zone) {
        DateTimeFormatter day = from.getYear() == to.getYear() ? DAY : DATE;
        String period = "from " + day.format(from) + " to " + day.format(to);
        if (history.days().isEmpty()) {
            out.println("No weigh-ins " + period + ".");
            return;
        }
        var columns = new ArrayList<Column>();
        columns.add(new Column("Date", false, true));
        columns.add(new Column("Time", false, false));
        columns.add(new Column("Weight", true, true));
        columns.add(new Column("Body fat", false, false));
        columns.add(new Column("Body water", false, false));
        columns.add(new Column("Muscle", true, false));
        columns.add(new Column("Bone", true, false));
        columns.add(new Column("BMI", false, false));
        columns.add(new Column("Visceral fat", false, false));
        columns.add(new Column("Metabolic age", false, false));
        columns.add(new Column("Weigh-ins", false, true));
        boolean marked = false;
        for (DayWeight d : history.days()) {
            WeighIn w = d.weight();
            WeighIn b = d.bodyComposition();
            Instant time = d.picked().time();
            List<String> cells = List.of(
                    day.format(d.date()),
                    time == null ? NONE : CLOCK.format(time.atZone(zone)),
                    w == null ? NONE : kilograms(w.weightKilograms()),
                    b == null || b.bodyFatPercent() == null ? NONE : format("%.1f%%", b.bodyFatPercent()),
                    b == null || b.bodyWaterPercent() == null ? NONE : format("%.1f%%", b.bodyWaterPercent()),
                    b == null || b.muscleMassKilograms() == null ? NONE : kilograms(b.muscleMassKilograms()),
                    b == null || b.boneMassKilograms() == null ? NONE : kilograms(b.boneMassKilograms()),
                    d.bmi() == null ? NONE : format("%.1f", d.bmi()),
                    b == null ? NONE : orNone(b.visceralFat()),
                    b == null || b.metabolicAgeYears() == null ? NONE : b.metabolicAgeYears() + " years",
                    d.weighIns() + (d.fromDifferentWeighIns() ? "*" : ""));
            marked |= d.fromDifferentWeighIns();
            for (int c = 0; c < columns.size(); c++) {
                columns.get(c).cells().add(cells.get(c));
            }
        }
        columns.removeIf(column -> !column.always() && column.cells().stream().allMatch(NONE::equals));
        var rows = new ArrayList<List<String>>();
        for (int r = 0; r < history.days().size(); r++) {
            int row = r;
            rows.add(columns.stream().map(column -> column.cells().get(row)).toList());
        }
        var rightAligned = new HashSet<Integer>();
        for (int c = 0; c < columns.size(); c++) {
            if (columns.get(c).rightAligned()) {
                rightAligned.add(c);
            }
        }
        table(columns.stream().map(Column::header).toList(), rows, rightAligned);
        if (marked) {
            out.println("* Body composition from another weigh-in that day than the weight.");
        }
        int count = history.days().size();
        out.println(count + (count == 1 ? " day" : " days") + " with a weigh-in " + period + ".");
        if (history.first() != null) {
            double change = history.changeKilograms();
            out.println("First " + weighed(history.first(), day) + ", last " + weighed(history.last(), day) + ": "
                    + (change == 0 ? "no change" : format("%+.1f kg", change)) + ".");
            out.println("Lowest " + weighed(history.lowest(), day) + ", highest " + weighed(history.highest(), day)
                    + ".");
        }
    }

    /**
     * The daily log, the same as {@code get_daily_log} gives, for checking: a table with a row for every day
     * (sleep, steps, calories eaten and weight), then a table of the days' activities, oldest first. A day that isn't
     * final (today) is marked with a *. Hours and minutes to 1 decimal place, as the health log has them.
     *
     * @param log  every day from {@code from} to {@code to}, oldest first
     * @param zone for the times of the weigh-ins: the Garmin profile's
     */
    void log(List<LogDay> log, LocalDate from, LocalDate to, ZoneId zone) {
        DateTimeFormatter day = from.getYear() == to.getYear() ? DAY : DATE;
        var rows = new ArrayList<List<String>>();
        var activities = new ArrayList<List<String>>();
        boolean marked = false;
        for (LogDay d : log) {
            Sleep night = d.sleep();
            DayWeight weight = d.weight();
            WeighIn w = weight == null ? null : weight.weight();
            WeighIn b = weight == null ? null : weight.bodyComposition();
            Instant time = weight == null ? null : weight.picked().time();
            rows.add(List.of(
                    day.format(d.date()) + (d.isFinal() ? "" : "*"),
                    night == null ? NONE : orNone(night.score()),
                    night == null || night.sleepSeconds() == null ? NONE
                            : format("%.1f", Units.decimalHours(night.sleepSeconds())),
                    d.steps() == null ? NONE : format("%,d", d.steps()),
                    d.caloriesEaten() == null ? NONE : format("%,d kcal", d.caloriesEaten()),
                    w == null ? NONE : kilograms(w.weightKilograms()),
                    b == null || b.bodyFatPercent() == null ? NONE : format("%.1f%%", b.bodyFatPercent()),
                    b == null || b.muscleMassKilograms() == null ? NONE : kilograms(b.muscleMassKilograms()),
                    time == null ? NONE : CLOCK.format(time.atZone(zone)),
                    weight == null ? NONE : String.valueOf(weight.weighIns())));
            marked |= !d.isFinal();
            for (LogDay.Activity a : d.activities()) {
                ActivitySummary s = a.activity();
                activities.add(List.of(
                        day.format(d.date()),
                        orNone(s.type()),
                        orNone(a.group()),
                        s.durationSeconds() == null ? NONE
                                : format("%.1f", Units.decimalMinutes(s.durationSeconds())),
                        orNone(s.name()),
                        String.valueOf(s.id())));
            }
        }
        table(List.of("Date", "Sleep", "Hours", "Steps", "Calories eaten", "Weight", "Body fat", "Muscle",
                "Weighed", "Weigh-ins"), rows, Set.of(1, 2, 3, 4, 5, 6, 7));
        if (marked) {
            out.println("* Not final: Garmin may still add to it, so don't log it yet.");
        }
        out.println();
        if (activities.isEmpty()) {
            out.println("No activities.");
        } else {
            table(List.of("Date", "Type", "Group", "Minutes", "Name", "Id"), activities, Set.of(3));
        }
        out.println();
        int count = log.size();
        out.println(count + (count == 1 ? " day" : " days") + " from " + day.format(from) + " to " + day.format(to)
                + ".");
        out.println("Sleep is the night that ended that morning. Calories eaten are food logged, not burned.");
    }

    /** "75.7 kg (Fri 23 Oct)" */
    private static String weighed(DayWeight day, DateTimeFormatter format) {
        return kilograms(day.weight().weightKilograms()) + " (" + format.format(day.date()) + ")";
    }

    /** One column of the weight table, filled in row by row. */
    private record Column(String header, boolean rightAligned, boolean always, List<String> cells) {

        Column(String header, boolean rightAligned, boolean always) {
            this(header, rightAligned, always, new ArrayList<>());
        }
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
     * The day's first weight, with the first body composition under it ({@link DayWeight}). If they come from
     * different weigh-ins, the weight line says so. Garmin gives muscle mass as a mass, so its share of the weight
     * is worked out, from the same weigh-in.
     *
     * @param weighIns oldest first
     */
    private static void weighIns(Lines lines, List<WeighIn> weighIns) {
        Optional<DayWeight> found = DayWeight.of(weighIns);
        if (found.isEmpty()) {
            return;
        }
        DayWeight day = found.get();
        String notes = joined("; ",
                day.weighIns() == 1 ? null : "first of " + day.weighIns() + " weigh-ins",
                day.fromDifferentWeighIns() ? "body composition from another weigh-in" : null);
        lines.add("Weight", (day.weight() == null ? "not recorded" : kilograms(day.weight().weightKilograms()))
                + (notes == null ? "" : " (" + notes + ")"));
        lines.add("  BMI", day.bmi() == null ? null : format("%.1f", day.bmi()));
        if (day.bodyComposition() != null) {
            bodyComposition(lines, day.bodyComposition());
        }
    }

    /** What only a scale measures. The BMI is the day's ({@link DayWeight#bmi()}). */
    private static void bodyComposition(Lines lines, WeighIn w) {
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

    /** h:mm:ss, or m:ss under an hour ({@link Units#duration}). "–" for none. */
    static String duration(Double seconds) {
        return orNone(Units.duration(seconds));
    }

    /** Always h:mm:ss ({@link Units#hours}), or null. */
    static String hours(Integer seconds) {
        return Units.hours(seconds);
    }

    /** min:ss per km ({@link Units#pace}). "–" for no speed. */
    static String pace(Double metresPerSecond) {
        return orNone(Units.pace(metresPerSecond));
    }

    private static Double positive(Double value) {
        return Units.positive(value);
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

    private static String label(String key) {
        return Units.label(key);
    }

    private static String orNone(Object value) {
        return value == null ? NONE : value.toString();
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ENGLISH, pattern, args);
    }
}
