package com.hammez.stride.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hammez.stride.garmin.ActivitySummary;

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
