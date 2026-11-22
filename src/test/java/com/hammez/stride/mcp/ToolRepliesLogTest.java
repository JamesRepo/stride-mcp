package com.hammez.stride.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hammez.stride.garmin.LogDay;

/**
 * get_daily_log's reply, built straight from LogDays: what GarminToolsTest can't set up through a fake Garmin.
 */
class ToolRepliesLogTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private final ToolReplies replies = new ToolReplies();

    @Test
    void theClientDecidesWhatIsPastSoACallPastMidnightAgreesWithItself() {
        // Review, 22 November 2026: the tool looked up today (the 20th) just before midnight, and the client just
        // after (the 21st), so to the client the 20th is a past day that hasn't synced. The reply goes by the client.
        var nov20 = LocalDate.of(2026, 11, 20);
        var day = new LogDay(nov20, LogDay.State.WATCH_NOT_SYNCED, LocalDateTime.of(2026, 11, 20, 8, 42), null, 95,
                null, List.of(), null);

        String reply = replies.json(replies.log(List.of(day), nov20, nov20, nov20, LONDON));

        assertThat(reply)
                .contains("{\"date\":\"2026-11-20\",\"final\":false,\"watchDataUntil\":\"08:42\",\"steps\":95}");
    }

    @Test
    void onlyADayTheWatchHasntSyncedSinceSaysWhereItsDataStops() {
        var nov19 = LocalDate.of(2026, 11, 19);
        var nov20 = nov19.plusDays(1);
        var until = LocalDateTime.of(2026, 11, 20, 8, 42);
        var log = List.of(
                new LogDay(nov19, LogDay.State.WATCH_NOT_SYNCED, null, null, null, null, List.of(), null),
                new LogDay(nov20, LogDay.State.TODAY, until, null, null, null, List.of(), null));

        String reply = replies.json(replies.log(log, nov19, nov20, nov20, LONDON));

        assertThat(reply).contains("{\"date\":\"2026-11-19\",\"final\":false,\"watchDataUntil\":\"none\"}",
                "{\"date\":\"2026-11-20\",\"final\":false}");
    }
}
