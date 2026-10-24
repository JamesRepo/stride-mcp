package com.hammez.stride.cli;

import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminSamples;
import com.hammez.stride.garmin.GarminSamples.Capture;
import com.hammez.stride.garmin.GarminSamples.Saved;
import com.hammez.stride.garmin.auth.GarminAuth;
import com.hammez.stride.garmin.auth.GarminAuth.WhoAmI;
import com.hammez.stride.garmin.auth.GarminException;

/**
 * The {@code login}, {@code whoami}, {@code logout} and {@code capture} commands. Runs one command and sets the exit code:
 * 0 for success, 1 for a failure, 2 for a usage error.
 */
public class StrideCommands implements ApplicationRunner, ExitCodeGenerator {

    static final String USAGE = """
            Usage: java -jar stride-mcp.jar <command>

            Commands:
              login             Sign in to Garmin in your browser and save the session
              whoami            Show who you're signed in as (refreshes the session if needed)
              logout            Delete the saved session and the cache
              capture <folder>  Save Garmin's raw replies (about 10 requests, once each) to files in a new
                                folder, for building test fixtures. The files hold personal data.
            """;

    static final String CHECKLIST = "the checklist in docs/phases/phase-2-data.md (\"Getting real samples\")";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm");

    private final GarminAuth auth;
    private final GarminSamples samples;
    private final GarminCache cache;
    private final CliIo io;
    private final ZoneId zone;
    private int exitCode;

    public StrideCommands(GarminAuth auth, GarminSamples samples, GarminCache cache, CliIo io, ZoneId zone) {
        this.auth = auth;
        this.samples = samples;
        this.cache = cache;
        this.io = io;
        this.zone = zone;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = run(args.getNonOptionArgs());
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    int run(List<String> args) {
        if (args.isEmpty() || args.size() != (args.getFirst().equals("capture") ? 2 : 1)) {
            io.err().print(USAGE);
            return 2;
        }
        Path folder = null;
        if (args.getFirst().equals("capture")) {
            folder = captureFolder(args.get(1));
            if (folder == null) {
                io.err().print(USAGE);
                return 2;
            }
        }
        try {
            switch (args.getFirst()) {
                case "login" -> login();
                case "capture" -> capture(folder);
                case "whoami" -> whoami();
                case "logout" -> logout();
                case "help", "--help", "-h" -> io.out().print(USAGE);
                default -> {
                    io.err().println("Unknown command: " + args.getFirst());
                    io.err().print(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (GarminException e) {
            io.err().println(e.getMessage());
            return 1;
        } catch (UncheckedIOException e) {
            io.err().println(e.getMessage() + ": " + e.getCause().getMessage());
            return 1;
        }
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

    /**
     * The capture folder, or null (after saying why) if the argument isn't one. Spring already takes
     * {@code --help} as an option, but {@code -h} would arrive here.
     */
    private Path captureFolder(String arg) {
        if (arg.isBlank() || arg.startsWith("-")) {
            io.err().println("capture needs a folder, like: stride-mcp capture ~/stride-samples");
            return null;
        }
        try {
            return Path.of(arg);
        } catch (InvalidPathException e) {
            io.err().println("Not a folder path: " + e.getMessage());
            return null;
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
