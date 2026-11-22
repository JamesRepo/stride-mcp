package com.hammez.stride.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hammez.stride.garmin.ActivitySummary;
import com.hammez.stride.garmin.DayWeight;
import com.hammez.stride.garmin.LogDay;
import com.hammez.stride.garmin.WeighIn;

/** The plan's example, and the CLI's units. DataCommandsTest covers the other layouts. */
class DataPrinterTest {

    @Test
    void theActivitiesExampleInThePlan() {
        // The two runs in activities.json, as in docs/phases/phase-2-data.md ("What they print").
        var runs = List.of(
                new ActivitySummary(24810777073L, "Islington Running", "running", LocalDateTime.of(2026, 10, 22, 7, 6),
                        Instant.parse("2026-10-22T06:06:50Z"), 5052.18994140625, 2161.051025390625, null, null,
                        2.3380000591278076, 143, 169, 448),
                new ActivitySummary(24765567036L, "Islington Running", "running", LocalDateTime.of(2026, 10, 18, 10, 7),
                        Instant.parse("2026-10-18T09:07:44Z"), 8009.6201171875, 3230.826904296875, null, null,
                        2.4790000915527344, 149, null, null));
        var bytes = new ByteArrayOutputStream();

        new DataPrinter(new PrintStream(bytes, true, StandardCharsets.UTF_8))
                .activities(runs, LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 23), "running");

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("""
                Date        Type     Name               Distance  Time   Pace      Avg HR  Id
                Thu 22 Oct  running  Islington Running   5.05 km  36:01  7:08 /km  143     24810777073
                Sun 18 Oct  running  Islington Running   8.01 km  53:51  6:43 /km  149     24765567036
                2 running activities in the last 7 days (Sat 17 Oct to Fri 23 Oct).
                """);
    }

    @Test
    void aLogDayWithOnlyBodyCompositionAndAnActivityWithNoGroupOrDuration() {
        var day = LocalDate.of(2026, 11, 12);
        var body = new WeighIn(day, Instant.parse("2026-11-12T07:10:00Z"), null, null, 18.04, null, 33.04, null,
                null, null);
        var activity = new ActivitySummary(7L, "Something new", "new_sport_2030", null, null, null, null, null, null,
                null, null, null, null);
        var log = List.of(new LogDay(day, LogDay.State.FINAL, day.plusDays(1).atStartOfDay(), null, null, null,
                List.of(new LogDay.Activity(activity, null)), new DayWeight(null, body, 1)));
        var bytes = new ByteArrayOutputStream();

        new DataPrinter(new PrintStream(bytes, true, StandardCharsets.UTF_8)).log(log, day, day,
                ZoneId.of("Europe/London"));

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("""
                Date        Sleep  Hours  Steps  Calories eaten  Weight  Body fat   Muscle  Weighed  Weigh-ins
                Thu 12 Nov      –      –      –               –       –     18.0%  33.0 kg  07:10    1

                Date        Type            Group  Minutes  Name           Id
                Thu 12 Nov  new_sport_2030  –            –  Something new  7

                1 day from Thu 12 Nov to Thu 12 Nov.
                Sleep is the night that ended that morning. Calories eaten are food logged, not burned.
                """);
    }

    @Test
    void pastDaysTheWatchHasntSyncedSinceSayWhereTheirDataStops() {
        // As on 22 November 2026: the watch last synced at 08:43 on the 20th.
        var fri = LocalDate.of(2026, 11, 20);
        var sat = fri.plusDays(1);
        var sun = sat.plusDays(1);
        var log = List.of(
                new LogDay(fri, LogDay.State.WATCH_NOT_SYNCED, LocalDateTime.of(2026, 11, 20, 8, 42), null, 95, null,
                        List.of(), null),
                new LogDay(sat, LogDay.State.WATCH_NOT_SYNCED, null, null, null, 3884, List.of(), null),
                new LogDay(sun, LogDay.State.TODAY, null, null, null, 1374, List.of(), null));
        var bytes = new ByteArrayOutputStream();

        new DataPrinter(new PrintStream(bytes, true, StandardCharsets.UTF_8)).log(log, fri, sun,
                ZoneId.of("Europe/London"));

        // Today gets the * but no line: it's never final.
        assertThat(bytes.toString(StandardCharsets.UTF_8)).startsWith("""
                Date         Sleep  Hours  Steps  Calories eaten  Weight  Body fat  Muscle  Weighed  Weigh-ins
                Fri 20 Nov*      –      –     95               –       –         –       –  –        –
                Sat 21 Nov*      –      –      –      3,884 kcal       –         –       –  –        –
                Sun 22 Nov*      –      –      –      1,374 kcal       –         –       –  –        –
                * Not final: Garmin may still add to it, so don't log it yet.
                  Fri 20 Nov: the watch's data stops at 08:42. Sync the watch, then run log again with --no-cache.
                  Sat 21 Nov: no watch data. Sync the watch, then run log again with --no-cache.

                No activities.
                """);
    }

    @Test
    void kilometresHaveTwoDecimals() {
        assertThat(DataPrinter.km(5052.19)).isEqualTo("5.05 km");
        assertThat(DataPrinter.km(0.0)).isEqualTo("0.00 km");
        assertThat(DataPrinter.km(null)).isEqualTo("–");
    }

    @Test
    void missingValuesAreADash() {
        // The rounding is UnitsTest's. Here: the CLI shows a missing value as "–".
        assertThat(DataPrinter.duration(59.6)).isEqualTo("1:00");
        assertThat(DataPrinter.duration(null)).isEqualTo("–");
        assertThat(DataPrinter.pace(2.338)).isEqualTo("7:08 /km");
        assertThat(DataPrinter.pace(0.0)).isEqualTo("–");
        assertThat(DataPrinter.pace(null)).isEqualTo("–");
        assertThat(DataPrinter.hours(null)).isNull();
    }
}
