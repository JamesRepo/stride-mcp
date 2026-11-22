package com.hammez.stride.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * The daily log's days from the dates given, for get_daily_log and the CLI's log alike. Today is 20 November.
 * GarminToolsTest and DataCommandsTest check what each one says about it.
 */
class LogRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate NOV_8 = LocalDate.of(2026, 11, 8);

    @Test
    void noDatesIsYesterday() {
        assertThat(LogRange.of(null, null, TODAY)).isEqualTo(new LogRange(YESTERDAY, YESTERDAY));
    }

    @Test
    void onlyToIsThatOneDay() {
        assertThat(LogRange.of(null, NOV_8, TODAY)).isEqualTo(new LogRange(NOV_8, NOV_8));
        assertThat(LogRange.of(null, TODAY, TODAY)).isEqualTo(new LogRange(TODAY, TODAY));
    }

    @Test
    void onlyFromGoesToYesterdayOrIsJustToday() {
        assertThat(LogRange.of(NOV_8, null, TODAY)).isEqualTo(new LogRange(NOV_8, YESTERDAY));
        assertThat(LogRange.of(YESTERDAY, null, TODAY)).isEqualTo(new LogRange(YESTERDAY, YESTERDAY));
        assertThat(LogRange.of(TODAY, null, TODAY)).isEqualTo(new LogRange(TODAY, TODAY));
    }

    @Test
    void bothDatesAreKeptAsTheyAreEvenBackwardsOrLong() {
        // The callers check the order and length, in their own words.
        assertThat(LogRange.of(TODAY, NOV_8, TODAY)).isEqualTo(new LogRange(TODAY, NOV_8));
        LocalDate longAgo = LocalDate.of(2025, 1, 1);
        assertThat(LogRange.of(longAgo, null, TODAY)).isEqualTo(new LogRange(longAgo, YESTERDAY));
    }

    @Test
    void aDayAfterTodayIsRefusedFromFirst() {
        LocalDate tomorrow = TODAY.plusDays(1);

        var from = catchThrowableOfType(LogRange.AfterToday.class, () -> LogRange.of(tomorrow, tomorrow, TODAY));
        var to = catchThrowableOfType(LogRange.AfterToday.class, () -> LogRange.of(NOV_8, tomorrow, TODAY));
        var onlyTo = catchThrowableOfType(LogRange.AfterToday.class, () -> LogRange.of(null, tomorrow, TODAY));

        assertThat(from.input()).isEqualTo("from");
        assertThat(from.date()).isEqualTo(tomorrow);
        assertThat(from.today()).isEqualTo(TODAY);
        assertThat(from).hasMessage("from 2026-11-21 is after today (2026-11-20)");
        assertThat(to.input()).isEqualTo("to");
        assertThat(onlyTo.input()).isEqualTo("to");
    }
}
