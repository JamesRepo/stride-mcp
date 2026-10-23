package com.hammez.stride.cli;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

import com.hammez.stride.garmin.auth.GarminAuth;
import com.hammez.stride.garmin.auth.GarminAuth.WhoAmI;
import com.hammez.stride.garmin.auth.GarminAuthException;

/**
 * The {@code login}, {@code whoami} and {@code logout} commands. Runs one command and sets the exit code:
 * 0 for success, 1 for a failure, 2 for a usage error.
 */
public class StrideCommands implements ApplicationRunner, ExitCodeGenerator {

    static final String USAGE = """
            Usage: java -jar stride-mcp.jar <command>

            Commands:
              login    Sign in to Garmin in your browser and save the session
              whoami   Show who you're signed in as (refreshes the session if needed)
              logout   Delete the saved session
            """;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm");

    private final GarminAuth auth;
    private final CliIo io;
    private final ZoneId zone;
    private int exitCode;

    public StrideCommands(GarminAuth auth, CliIo io, ZoneId zone) {
        this.auth = auth;
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
        if (args.size() != 1) {
            io.err().print(USAGE);
            return 2;
        }
        try {
            switch (args.getFirst()) {
                case "login" -> login();
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
        } catch (GarminAuthException e) {
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

    private void logout() {
        io.out().println(auth.logout() ? "Logged out. Deleted " + auth.tokenFile() : "Already logged out.");
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
