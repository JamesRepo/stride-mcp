package com.hammez.stride.garmin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The real (stripped) Garmin replies in src/test/resources/garmin. Public for the CLI tests. */
public final class Fixtures {

    private Fixtures() {
    }

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
