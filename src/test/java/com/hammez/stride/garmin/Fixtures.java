package com.hammez.stride.garmin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The real (stripped) Garmin replies in src/test/resources/garmin, and one made up. Public for the CLI tests. */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * A day with two weigh-ins. <b>Made up</b>, as no real reply has been captured yet: shaped as
     * python-garminconnect reads it (October 2026). Masses in grams. Listed latest first, so tests see them sorted.
     */
    public static final String TWO_WEIGH_INS = """
            {"startDate":"2026-10-23","endDate":"2026-10-23","dateWeightList":[
             {"samplePk":2,"calendarDate":"2026-10-23","weight":75400.0,"bmi":23.27,"bodyFat":18.6,"bodyWater":55.1,
              "boneMass":3200.0,"muscleMass":33100.0,"physiqueRating":null,"visceralFat":null,"metabolicAge":null,
              "sourceType":"INDEX_SCALE","timestampGMT":1792737900000},
             {"samplePk":1,"calendarDate":"2026-10-23","weight":75900.0,"bmi":null,"bodyFat":null,"bodyWater":null,
              "boneMass":null,"muscleMass":null,"sourceType":"MANUAL","timestampGMT":1792737600000}],
             "totalAverage":{"weight":75650.0}}""";

    /** One reply's body, such as {@code read("sleep-last-night.json")}. */
    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/garmin/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
