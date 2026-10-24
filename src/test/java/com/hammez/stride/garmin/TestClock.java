package com.hammez.stride.garmin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when a test says so, for checking cache lifetimes without sleeping. */
final class TestClock extends Clock {

    private Instant now;

    TestClock(Instant now) {
        this.now = now;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // GarminClient asks for a zone to work out "today". Keep following this clock's time.
        TestClock self = this;
        return new Clock() {
            @Override
            public Instant instant() {
                return self.instant();
            }

            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }
        };
    }
}
