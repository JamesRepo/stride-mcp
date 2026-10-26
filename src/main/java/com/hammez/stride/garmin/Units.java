package com.hammez.stride.garmin;

import java.util.Locale;

/**
 * Garmin's values as people read them: durations, pace and labels. Shared by the CLI's printing and the MCP tools'
 * replies, so both show the same numbers for the same activity. Each returns null for a missing value; the CLI
 * shows that as "–", the tools leave it out.
 */
public final class Units {

    private Units() {
    }

    /** h:mm:ss, or m:ss under an hour. Rounded to the second. */
    public static String duration(Double seconds) {
        if (seconds == null) {
            return null;
        }
        long total = Math.round(seconds);
        long h = total / 3600;
        long m = total % 3600 / 60;
        long s = total % 60;
        return h > 0 ? format("%d:%02d:%02d", h, m, s) : format("%d:%02d", m, s);
    }

    /** Always h:mm:ss, so 8 minutes can't be read as 8 hours. For sleep. */
    public static String hours(Integer seconds) {
        if (seconds == null) {
            return null;
        }
        return format("%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    /** min:ss per km, such as {@code 7:08 /km}. Null for no speed (null, or 0 or less). */
    public static String pace(Double metresPerSecond) {
        if (metresPerSecond == null || metresPerSecond <= 0) {
            return null;
        }
        long secondsPerKm = Math.round(1000 / metresPerSecond);
        return format("%d:%02d /km", secondsPerKm / 60, secondsPerKm % 60);
    }

    /** Null for 0 or less: Garmin's way of saying "doesn't apply" for an activity's distance, speed or time. */
    public static Double positive(Double value) {
        return value == null || value <= 0 ? null : value;
    }

    /** Garmin's {@code EXCELLENT} as "Excellent". */
    public static String label(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String words = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
