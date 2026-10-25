package com.hammez.stride.cli;

import java.util.ArrayList;
import java.util.List;

import com.hammez.stride.garmin.GarminDataConfig;

/**
 * The command line, read from the raw arguments in order, so both {@code --days 7} and {@code --days=7} work.
 * Spring only sees {@code --days=7} as one option: with a space it splits off the {@code 7}, and the order of
 * {@link org.springframework.boot.ApplicationArguments}' lists can't pair them up again.
 * <p>
 * An option with a dot in its name, such as {@code --stride.token-file=…}, is a Spring setting: Spring has
 * already read it, so it is skipped here. Any other option stride-mcp doesn't know is a usage error, so a typo
 * like {@code --day 7} isn't silently ignored.
 *
 * @param command   the first argument that isn't an option, or null if there is none
 * @param arguments the other arguments that aren't options
 * @param days      {@code --days}, checked to be 1 to {@link GarminDataConfig#MAX_RANGE_DAYS}, or null if not given
 * @param type      {@code --type}, or null if not given
 * @param noCache   {@code --no-cache}
 * @param help      {@code --help}
 */
record CommandLine(String command, List<String> arguments, Integer days, String type, boolean noCache,
        boolean help) {

    static final String DAYS_RANGE = "--days must be between 1 and " + GarminDataConfig.MAX_RANGE_DAYS + ".";

    CommandLine {
        arguments = List.copyOf(arguments);
    }

    /** @throws UsageException for an unknown option, an option given twice, or a bad or missing value */
    static CommandLine parse(List<String> args) {
        var plain = new ArrayList<String>();
        String days = null;
        String type = null;
        boolean noCache = false;
        boolean help = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("--")) {
                plain.add(arg);
                continue;
            }
            int eq = arg.indexOf('=');
            String name = eq < 0 ? arg.substring(2) : arg.substring(2, eq);
            String value = eq < 0 ? null : arg.substring(eq + 1);
            switch (name) {
                case "days", "type" -> {
                    if (value == null && i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                        value = args.get(++i);
                    }
                    if (name.equals("days") ? days != null : type != null) {
                        throw new UsageException("--" + name + " is given twice.", true);
                    }
                    // A missing value is "", so it gets the same message as a bad one.
                    if (name.equals("days")) {
                        days = value == null ? "" : value;
                    } else {
                        type = value == null ? "" : value;
                    }
                }
                case "no-cache", "help" -> {
                    if (value != null) {
                        throw new UsageException("--" + name + " doesn't take a value.", true);
                    }
                    if (name.equals("help")) {
                        help = true;
                    } else {
                        noCache = true;
                    }
                }
                default -> {
                    if (!name.contains(".")) {
                        throw new UsageException("Unknown option: --" + name, true);
                    }
                }
            }
        }
        if (type != null && type.isBlank()) {
            throw new UsageException("--type needs an activity type, like --type running.", false);
        }
        String command = plain.isEmpty() ? null : plain.removeFirst();
        return new CommandLine(command, plain, days == null ? null : days(days), type, noCache, help);
    }

    private static int days(String value) {
        try {
            int days = Integer.parseInt(value);
            if (days >= 1 && days <= GarminDataConfig.MAX_RANGE_DAYS) {
                return days;
            }
        } catch (NumberFormatException e) {
            // Not a number: same message as out of range.
        }
        throw new UsageException(DAYS_RANGE, false);
    }
}
