package com.hammez.stride.cli;

import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

import com.hammez.stride.garmin.ActivitySummary;
import com.hammez.stride.garmin.DailySummary;
import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.GarminSamples;
import com.hammez.stride.garmin.GarminSamples.Capture;
import com.hammez.stride.garmin.GarminSamples.Saved;
import com.hammez.stride.garmin.Sleep;
import com.hammez.stride.garmin.UnexpectedReplyException;
import com.hammez.stride.garmin.WeighIn;
import com.hammez.stride.garmin.auth.GarminAuth;
import com.hammez.stride.garmin.auth.GarminAuth.WhoAmI;
import com.hammez.stride.garmin.auth.GarminException;
import com.hammez.stride.garmin.auth.NotFoundException;

/**
 * The commands: {@code login}, {@code whoami}, {@code logout}, the data commands ({@code activities},
 * {@code activity}, {@code summary}, {@code sleep}) and {@code capture}. Runs one command and sets the exit code:
 * 0 for success, 1 for a failure, 2 for a usage error.
 * <p>
 * The data commands go through {@link GarminClient}, so the cache answers when it can. After one, a reason the
 * cache couldn't be used goes to stderr, so it isn't silent; the command still succeeds.
 */
public class StrideCommands implements ApplicationRunner, ExitCodeGenerator {

    static final String USAGE = """
            Usage: java -jar stride-mcp.jar <command> [options]

            Commands:
              login             Sign in to Garmin in your browser and save the session
              whoami            Show who you're signed in as (refreshes the session if needed)
              logout            Delete the saved session and the cache
              activities        Your activities from the last 7 days, newest first
                --days N          The last N days instead, today included (1 to %d)
                --type T          Only this type, as Garmin names it: running, cycling, trail_running...
              activity <id>     One activity: totals, heart rate, training effect and laps. The id is in activities.
              summary [date]    One day's totals: steps, calories, heart rate, stress, Body Battery, the sleep
                                that ended that morning, and weight and body composition. Default today.
              sleep [date]      The night that ended on that morning. Default last night.
              capture <folder>  Save Garmin's raw replies (about 13 requests, once each) to files in a new
                                folder, for building test fixtures. The files hold personal data.
              serve             Start the MCP server, over stdin and stdout. An AI app runs this, not you.

            Dates look like 2026-10-22. "Today" is today in your Garmin profile's time zone.
            --no-cache            With activities, activity, summary or sleep: ask Garmin even if the cache
                                  has a fresh copy. The new reply is still cached.
            """.formatted(GarminDataConfig.MAX_RANGE_DAYS);

    public static final String SERVE_ARGUMENTS =
            "serve takes no arguments or options, only Spring settings such as --stride.token-file=…";

    private static final Set<String> DATA_COMMANDS = Set.of("activities", "activity", "summary", "sleep");
    private static final int DEFAULT_DAYS = 7;

    static final String CHECKLIST = "the checklist in docs/phases/phase-2-data.md (\"Getting real samples\")";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm");

    private final GarminAuth auth;
    private final GarminSamples samples;
    private final GarminCache cache;
    private final GarminClient client;
    private final CliIo io;
    private final ZoneId zone;
    private int exitCode;

    /** @param zone the computer's, for the token times {@code login} and {@code whoami} print */
    public StrideCommands(GarminAuth auth, GarminSamples samples, GarminCache cache, GarminClient client, CliIo io,
            ZoneId zone) {
        this.auth = auth;
        this.samples = samples;
        this.cache = cache;
        this.client = client;
        this.io = io;
        this.zone = zone;
    }

    /**
     * True if the command line is {@code serve} and nothing else but Spring settings. {@code main} asks this before
     * Spring starts. Anything else, a wrong {@code serve} line included, is left to the commands to report, so both
     * modes read the command line the same way.
     */
    public static boolean isServe(String[] args) {
        try {
            CommandLine line = CommandLine.parse(Arrays.asList(args));
            return "serve".equals(line.command()) && line.arguments().isEmpty() && !line.help() && !line.noCache()
                    && line.days() == null && line.type() == null;
        } catch (UsageException e) {
            return false;
        }
    }

    /** Reads the raw arguments, not Spring's split of them: see {@link CommandLine}. */
    @Override
    public void run(ApplicationArguments args) {
        exitCode = run(Arrays.asList(args.getSourceArgs()));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    int run(List<String> args) {
        CommandLine line;
        Runnable command;
        try {
            line = CommandLine.parse(args);
            if (line.help()) {
                io.out().print(USAGE);
                return 0;
            }
            command = command(line);
        } catch (UsageException e) {
            if (e.getMessage() != null) {
                io.err().println(e.getMessage());
            }
            if (e.showUsage()) {
                io.err().print(USAGE);
            }
            return 2;
        }
        boolean data = DATA_COMMANDS.contains(line.command());
        try {
            command.run();
            return 0;
        } catch (GarminException e) {
            io.err().println(e.getMessage());
            return 1;
        } catch (UncheckedIOException e) {
            io.err().println(e.getMessage() + ": " + e.getCause().getMessage());
            return 1;
        } finally {
            if (data) {
                cache.problem().ifPresent(io.err()::println);
            }
        }
    }

    /**
     * The command to run, with its arguments checked, so nothing is sent to Garmin for a command line that's wrong.
     *
     * @throws UsageException if the command line is wrong
     */
    private Runnable command(CommandLine line) {
        String name = line.command();
        if (name == null) {
            throw new UsageException(null, true);
        }
        if (line.noCache() && !DATA_COMMANDS.contains(name)) {
            throw new UsageException("--no-cache only works with activities, activity, summary and sleep.", true);
        }
        if ((line.days() != null || line.type() != null) && !name.equals("activities")) {
            throw new UsageException("--days and --type only work with activities.", true);
        }
        GarminClient data = line.noCache() ? client.skippingCache() : client;
        return switch (name) {
            case "login" -> {
                arguments(line, 0, 0);
                yield this::login;
            }
            case "whoami" -> {
                arguments(line, 0, 0);
                yield this::whoami;
            }
            case "logout" -> {
                arguments(line, 0, 0);
                yield this::logout;
            }
            case "activities" -> {
                arguments(line, 0, 0);
                int days = line.days() == null ? DEFAULT_DAYS : line.days();
                yield () -> activities(data, days, line.type());
            }
            case "activity" -> {
                arguments(line, 1, 1);
                long id = activityId(line.arguments().getFirst());
                yield () -> new DataPrinter(io.out()).activity(data.activity(id));
            }
            case "summary" -> {
                arguments(line, 0, 1);
                LocalDate date = date(line);
                yield () -> summary(data, date);
            }
            case "sleep" -> {
                arguments(line, 0, 1);
                LocalDate date = date(line);
                yield () -> sleep(data, date);
            }
            case "capture" -> {
                arguments(line, 1, 1);
                Path folder = captureFolder(line.arguments().getFirst());
                yield () -> capture(folder);
            }
            // Only a wrong serve line gets here: a right one never starts the commands. See isServe.
            case "serve" -> throw new UsageException(SERVE_ARGUMENTS, true);
            case "help", "-h" -> {
                arguments(line, 0, 0);
                yield () -> io.out().print(USAGE);
            }
            default -> throw new UsageException("Unknown command: " + name, true);
        };
    }

    private static void arguments(CommandLine line, int min, int max) {
        int count = line.arguments().size();
        if (count < min || count > max) {
            throw new UsageException(null, true);
        }
    }

    /** A positive whole number. */
    private static long activityId(String arg) {
        try {
            if (arg.chars().allMatch(Character::isDigit)) {
                long id = Long.parseLong(arg);
                if (id > 0) {
                    return id;
                }
            }
        } catch (NumberFormatException e) {
            // Too long: same message.
        }
        throw new UsageException("Activity ids are numbers, like 20123456789.", false);
    }

    /** The date argument, or null for the default (today). */
    private static LocalDate date(CommandLine line) {
        if (line.arguments().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(line.arguments().getFirst());
        } catch (DateTimeParseException e) {
            throw new UsageException("Dates look like 2026-10-22.", false);
        }
    }

    /** Today and the {@code days - 1} days before it, in the Garmin profile's time zone. */
    private void activities(GarminClient data, int days, String type) {
        LocalDate to = data.today();
        LocalDate from = to.minusDays(days - 1);
        List<ActivitySummary> found = data.activities(from, to, type);
        new DataPrinter(io.out()).activities(found, from, to, type);
    }

    /**
     * The day's totals, the night that ended that morning, and the day's weigh-ins. No date means today.
     * The totals come first and any error in them stops the command, as before. If the sleep or weigh-ins can't be
     * read (a reply we don't understand, or a 404), they are left out with a line on stderr, so the totals still
     * show. Other errors (not logged in, 403, 429, no connection) still stop it.
     */
    private void summary(GarminClient data, LocalDate date) {
        LocalDate day = date == null ? data.today() : date;
        DailySummary totals = data.dailySummary(day);
        Optional<Sleep> sleep = part("sleep", () -> data.sleep(day)).orElse(Optional.empty());
        List<WeighIn> weighIns = part("weight", () -> data.weighIns(day)).orElse(List.of());
        new DataPrinter(io.out()).summary(totals, sleep, weighIns);
    }

    /** One part of the summary, or empty (with a line on stderr) if Garmin's reply for it can't be read. */
    private <T> Optional<T> part(String name, Supplier<T> fetch) {
        try {
            return Optional.of(fetch.get());
        } catch (UnexpectedReplyException | NotFoundException e) {
            io.err().println("Left out " + name + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** No date means last night: the night that ended this morning. */
    private void sleep(GarminClient data, LocalDate date) {
        LocalDate night = date == null ? data.today() : date;
        new DataPrinter(io.out()).sleep(night, data.sleep(night), data.zone());
    }

    private void login() {
        WhoAmI me = auth.login(io.in(), io.out());
        io.out().println();
        io.out().println("Logged in as " + me.profile().friendlyName() + ".");
        io.out().println("Tokens saved to " + auth.tokenFile());
        printSession(me);
    }

    private void whoami() {
        WhoAmI me = auth.whoami();
        io.out().println("Logged in as " + me.profile().friendlyName() + ".");
        printSession(me);
    }

    /**
     * Deletes the cache, then the tokens: logging out should leave no personal data behind. The cache goes first so
     * that a token file that can't be deleted doesn't keep the data too.
     */
    private void logout() {
        boolean hadCache = cache.clear();
        io.out().println(auth.logout() ? "Logged out. Deleted " + auth.tokenFile() : "Already logged out.");
        if (hadCache) {
            io.out().println("Deleted the cache in " + cache.dir());
        }
    }

    /** The capture folder. Spring takes {@code --help} as an option, but {@code -h} would arrive here. */
    private static Path captureFolder(String arg) {
        if (arg.isBlank() || arg.startsWith("-")) {
            throw new UsageException("capture needs a folder, like: stride-mcp capture ~/stride-samples", true);
        }
        try {
            return Path.of(arg);
        } catch (InvalidPathException e) {
            throw new UsageException("Not a folder path: " + e.getMessage(), true);
        }
    }

    private void capture(Path folder) {
        Capture capture = samples.capture(folder);
        io.out().println("Saved " + capture.saved().size() + " files to " + capture.folder() + ":");
        for (Saved saved : capture.saved()) {
            io.out().printf("  %-28s HTTP %d%n", saved.file(), saved.status());
        }
        capture.notes().forEach(io.out()::println);
        io.out().println();
        io.out().println("These files hold personal data: names, ids, heart rate, GPS start points, devices.");
        io.out().println("Before sending them, strip them using " + CHECKLIST + ".");
    }

    private void printSession(WhoAmI me) {
        io.out().println("Access token valid until " + format(me.accessTokenExpiresAt())
                + " (refreshed automatically).");
        if (me.sessionExpiresAt() == null) {
            io.out().println("Garmin didn't say when the session ends. Use stride-mcp at least monthly to keep it going.");
        } else {
            io.out().println("Session valid until " + format(me.sessionExpiresAt())
                    + ". Use stride-mcp before then to keep it going.");
        }
    }

    private String format(Instant instant) {
        return instant == null ? "unknown" : TIME.format(instant.atZone(zone));
    }
}
