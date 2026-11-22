package com.hammez.stride.garmin;

import java.time.LocalDate;

/**
 * The days a daily log covers, worked out from the dates given (the owner's choices, 22 November 2026). Shared by
 * {@code get_daily_log} and the CLI's {@code log}, so the command always shows what the tool does:
 * <ul>
 * <li>no dates: yesterday, the day just finished, which is the one to log;</li>
 * <li>only {@code to}: the {@link GarminDataConfig#DEFAULT_LOG_RANGE_DAYS} days ending there, so that one day;</li>
 * <li>only {@code from}: from there to yesterday, or just that day if it's today;</li>
 * <li>no day after today: Garmin has nothing for it yet.</li>
 * </ul>
 * It doesn't check the range's length or order: each caller does that, in its own words, against
 * {@link GarminDataConfig#MAX_LOG_RANGE_DAYS}.
 *
 * @param from first day, included
 * @param to   last day, included
 */
public record LogRange(LocalDate from, LocalDate to) {

    /**
     * @param from  null if not given
     * @param to    null if not given
     * @param today in the Garmin profile's time zone
     * @throws AfterToday if {@code from} or {@code to} is after today ({@code from} is checked first)
     */
    public static LogRange of(LocalDate from, LocalDate to, LocalDate today) {
        afterToday("from", from, today);
        afterToday("to", to, today);
        if (to == null) {
            LocalDate yesterday = today.minusDays(1);
            to = from != null && from.isAfter(yesterday) ? from : yesterday;
        }
        if (from == null) {
            from = to.minusDays(GarminDataConfig.DEFAULT_LOG_RANGE_DAYS - 1);
        }
        return new LogRange(from, to);
    }

    private static void afterToday(String input, LocalDate date, LocalDate today) {
        if (date != null && date.isAfter(today)) {
            throw new AfterToday(input, date, today);
        }
    }

    /**
     * A date given for the log is after today. Each caller turns it into its own error, from these values.
     *
     * @param input which date: {@code from} or {@code to}
     */
    public static final class AfterToday extends IllegalArgumentException {

        private final String input;
        private final LocalDate date;
        private final LocalDate today;

        AfterToday(String input, LocalDate date, LocalDate today) {
            super(input + " " + date + " is after today (" + today + ")");
            this.input = input;
            this.date = date;
            this.today = today;
        }

        public String input() {
            return input;
        }

        public LocalDate date() {
            return date;
        }

        public LocalDate today() {
            return today;
        }
    }
}
