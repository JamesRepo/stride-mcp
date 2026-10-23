package com.hammez.stride.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.garmin.auth.BrowserLogin;
import com.hammez.stride.garmin.auth.GarminAuth;
import com.hammez.stride.garmin.auth.GarminAuthConfig;
import com.hammez.stride.garmin.auth.GarminTokens;
import com.hammez.stride.garmin.auth.TokenManager;
import com.hammez.stride.garmin.auth.TokenStore;

import tools.jackson.databind.json.JsonMapper;

/** Command handling only. These tests never reach a network call. */
class StrideCommandsTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private TokenStore store;
    private StrideCommands commands;

    @BeforeEach
    void setUp() {
        store = new TokenStore(temp.resolve("garmin_tokens.json"), JsonMapper.builder().build());
        // Nothing here should talk to Garmin, so the HTTP pieces are left out.
        var auth = new GarminAuth(new BrowserLogin(GarminAuthConfig.defaults(), url -> true), null, null,
                new TokenManager(store, null, GarminAuthConfig.defaults(), Clock.systemUTC()), store);
        commands = new StrideCommands(auth,
                new CliIo(new BufferedReader(new StringReader("not an address\n")),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8)),
                ZoneOffset.UTC);
    }

    @Test
    void noCommandPrintsUsage() {
        assertThat(commands.run(List.of())).isEqualTo(2);
        assertThat(err()).contains("Usage:");
    }

    @Test
    void unknownCommandPrintsUsage() {
        assertThat(commands.run(List.of("fetch"))).isEqualTo(2);
        assertThat(err()).contains("Unknown command: fetch", "Usage:");
    }

    @Test
    void helpPrintsUsage() {
        assertThat(commands.run(List.of("help"))).isZero();
        assertThat(out()).contains("login", "whoami", "logout");
    }

    @Test
    void errorsGoToStderrWithExitCodeOne() {
        assertThat(commands.run(List.of("login"))).isEqualTo(1);
        assertThat(err()).contains("No ticket found");
    }

    @Test
    void logout() {
        store.save(new GarminTokens("access-secret", "refresh-secret", "C", Instant.EPOCH, null));

        assertThat(commands.run(List.of("logout"))).isZero();
        assertThat(commands.run(List.of("logout"))).isZero();

        assertThat(out()).contains("Logged out.", "Already logged out.").doesNotContain("secret");
        assertThat(store.load()).isEmpty();
    }

    @Test
    void whoamiWhenNotLoggedIn() {
        assertThat(commands.run(List.of("whoami"))).isEqualTo(1);
        assertThat(err()).contains("Not logged in. Run `stride-mcp login`.");
    }

    @Test
    void unreadableTokenFileIsAClearErrorAndLogoutStillWorks() throws Exception {
        Files.writeString(store.file(), "{\"accessToken\":\"access-secret\"");

        assertThat(commands.run(List.of("whoami"))).isEqualTo(1);
        assertThat(err()).contains("unreadable").doesNotContain("secret");

        assertThat(commands.run(List.of("logout"))).isZero();
        assertThat(out()).contains("Logged out.");
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
