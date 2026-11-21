package com.hammez.stride;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITIES;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITY;
import static com.hammez.stride.mcp.ToolFixtures.DAY_WITH_TWO_WEIGH_INS;
import static com.hammez.stride.mcp.ToolFixtures.LAST_NIGHT;
import static com.hammez.stride.mcp.ToolFixtures.LAST_SEVEN_DAYS;
import static com.hammez.stride.mcp.ToolFixtures.NOW;
import static com.hammez.stride.mcp.ToolFixtures.PERSONAL;
import static com.hammez.stride.mcp.ToolFixtures.SLEEP;
import static com.hammez.stride.mcp.ToolFixtures.SPLITS;
import static com.hammez.stride.mcp.ToolFixtures.STAIR_STEPPER;
import static com.hammez.stride.mcp.ToolFixtures.SUMMARY;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_PATH;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_RANGE;
import static com.hammez.stride.mcp.ToolFixtures.WEIGHT_SINCE_OCTOBER;
import static com.hammez.stride.mcp.ToolFixtures.compact;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.cli.StrideCommands;
import com.hammez.stride.garmin.Fixtures;
import com.hammez.stride.garmin.auth.FakeGarmin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code serve} as an AI app runs it: a separate Java process, talked to over its stdin and stdout. Nothing here
 * talks to the real Garmin. Most tests have no token file, so the tool calls are refused for their input or for not
 * being logged in. The ones that need Garmin's replies get a {@link FakeGarmin} in this JVM, through
 * {@link FakeGarminServe}, and its fake tokens.
 * <p>
 * Every process is also told to send http and https through a proxy that isn't there. If a test missed the fake,
 * its calls to Garmin would fail instead of reaching it. The fake is on this computer, which Java never sends
 * through a proxy (the default {@code http.nonProxyHosts}).
 */
class ServeModeTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
            "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";
    private static final String INITIALIZED = """
            {"jsonrpc":"2.0","method":"notifications/initialized"}""";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}""";

    @TempDir
    Path temp;

    private final JsonMapper json = JsonMapper.builder().build();
    private final FakeGarmin garmin = new FakeGarmin();
    private Process process;

    @AfterEach
    void stop() {
        if (process != null) {
            process.destroyForcibly();
        }
        garmin.close();
    }

    @Test
    void answersInitializeAndToolsListWithOnlyJsonRpcOnStdout() throws Exception {
        var server = start("serve", "--spring.main.sources=com.hammez.stride.NoisyStartup");

        server.send(INITIALIZE);
        JsonNode initialize = server.reply();
        server.send(INITIALIZED);
        server.send(TOOLS_LIST);
        JsonNode toolsList = server.reply();

        assertThat(initialize.path("id").asInt()).isEqualTo(1);
        JsonNode result = initialize.path("result");
        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("stride-mcp");
        assertThat(result.path("instructions").asString()).contains("stride-mcp login", "time zone");
        // Tools only. The SDK always adds logging.
        assertThat(result.path("capabilities").propertyNames()).containsExactlyInAnyOrder("tools", "logging");
        assertThat(result.path("capabilities").path("tools").path("listChanged").asBoolean()).isFalse();

        assertThat(toolsList.path("id").asInt()).isEqualTo(2);
        // The five tools, read-only, each with a description (GarminToolsTest checks the wording).
        JsonNode tools = toolsList.path("result").path("tools");
        assertThat(tools.valueStream().map(tool -> tool.path("name").asString()))
                .containsExactlyInAnyOrder("get_activities", "get_activity", "get_daily_summary", "get_sleep",
                        "get_weight");
        for (JsonNode tool : tools) {
            assertThat(tool.path("description").asString()).as(tool.toString()).isNotBlank();
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).as(tool.toString()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(tool.toString())
                    .isFalse();
        }

        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        // Every line on stdout was a JSON-RPC message. The stray println went to stderr, the warning to the log.
        assertOnlyJsonRpcOnStdout(server, 2);
        assertThat(server.stderr()).contains(NoisyStartup.PRINTED).doesNotContain(NoisyStartup.LOGGED);
        assertThat(Files.readString(logFile())).contains(NoisyStartup.LOGGED);
    }

    @Test
    void badInputsAreToolErrorsAndOneLogLineEach() throws Exception {
        // Not logged in (no token file), so anything that got past the input checks would fail differently.
        var server = start("serve");
        server.send(INITIALIZE);
        server.reply();
        server.send(INITIALIZED);

        server.send("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_sleep",\
                "arguments":{"date":"yesterday"}}}""");
        JsonNode badDate = server.reply().path("result");
        // A missing required input is refused by the MCP SDK, which checks arguments against the input schema
        // before the tool runs, in its own words.
        server.send("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_activity","arguments":{}}}""");
        JsonNode noId = server.reply().path("result");
        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        assertThat(badDate.path("isError").asBoolean()).isTrue();
        assertThat(badDate.path("content").get(0).path("text").asString())
                .isEqualTo("`date` isn't a date. Dates look like 2026-10-22.");
        assertThat(noId.path("isError").asBoolean()).isTrue();
        assertThat(noId.path("content").get(0).path("text").asString())
                .startsWith("Tool (get_activity) input validation failed:").contains("id");
        String log = Files.readString(logFile());
        assertThat(log).containsPattern("get_sleep refused its input in \\d+ ms");
        assertThat(log).doesNotContain("yesterday");
    }

    @Test
    void notLoggedInIsOurTextSaidOnce() throws Exception {
        var server = start("serve");
        server.send(INITIALIZE);
        server.reply();
        server.send(INITIALIZED);

        server.send("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_sleep","arguments":{}}}""");
        JsonNode result = server.reply().path("result");
        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        // Not Spring AI's default text, which said the message twice.
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").get(0).path("text").asString())
                .isEqualTo("Not logged in. Run `stride-mcp login`.");
        assertThat(Files.readString(logFile())).containsPattern("get_sleep failed in \\d+ ms: Not logged in\\. Run `stride-mcp login`\\.");
    }

    @Test
    void aToolCallGetsGarminsReplyAndAPrintlnDuringItCantBreakTheConnection() throws Exception {
        garmin.respond(PROFILE_PATH, 200, Fixtures.read("social-profile.json"))
                .respond(PERSONAL, 200, Fixtures.read("personal-information.json"))
                .respond(SLEEP, Map.of("date", "2026-10-23", "nonSleepBufferMinutes", "60"), 200,
                        Fixtures.read("sleep-last-night.json"));
        var server = startWithFakeGarmin();
        server.send(INITIALIZE);
        server.reply();
        server.send(INITIALIZED);

        // The clock prints each time a tool reads it.
        server.send("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_sleep",\
                "arguments":{"date":"2026-10-23"}}}""");
        JsonNode sleep = server.reply();
        // Still working after the println.
        server.send(TOOLS_LIST);
        JsonNode toolsList = server.reply();
        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        assertThat(sleep.path("id").asInt()).isEqualTo(3);
        assertThat(sleep.path("result").path("isError").asBoolean()).isFalse();
        // The same reply GarminToolsTest pins for this call.
        assertThat(text(sleep)).isEqualTo(compact(LAST_NIGHT));
        assertThat(garmin.requests(SLEEP)).singleElement().satisfies(request -> assertThat(
                request.header("Authorization")).isEqualTo("Bearer " + FakeGarmin.ACCESS_TOKEN));
        assertThat(toolsList.path("id").asInt()).isEqualTo(2);
        assertThat(toolsList.path("result").path("tools")).hasSize(5);

        assertOnlyJsonRpcOnStdout(server, 3);
        assertThat(server.stderr()).contains(FakeGarminServe.PRINTED);
        assertThat(Files.readString(logFile())).containsPattern("get_sleep worked in \\d+ ms");
        assertNoTokens(server);
    }

    @Test
    void toolCallsSentTogetherRunAtOnceAndEachReplyHasItsRequestsId() throws Exception {
        garmin.respond(PROFILE_PATH, 200, Fixtures.read("social-profile.json"))
                .respond(PERSONAL, 200, Fixtures.read("personal-information.json"))
                .respond(ACTIVITIES, 200, Fixtures.read("activities.json"))
                .respond(ACTIVITY, 200, Fixtures.read("activity.json"))
                .respond(SPLITS, 200, Fixtures.read("activity-splits.json"))
                .respond(SUMMARY, 200, Fixtures.read("summary-today.json"))
                .respond(WEIGHT_PATH + "2026-10-23", 200, Fixtures.TWO_WEIGH_INS)
                .respond(SLEEP, 200, Fixtures.read("sleep-last-night.json"))
                .respond(WEIGHT_RANGE, 200, Fixtures.read("weight-range.json"))
                // Answered one after another, the first of these would wait for the other until it gave up.
                .together(SUMMARY, SLEEP);
        var server = startWithFakeGarmin();
        server.send(INITIALIZE);
        server.reply();
        server.send(INITIALIZED);

        // All five sent before any reply is read, as an AI app may. Each reply is the one GarminToolsTest pins.
        var expected = Map.of(
                10, LAST_SEVEN_DAYS,
                11, STAIR_STEPPER,
                12, DAY_WITH_TWO_WEIGH_INS,
                13, LAST_NIGHT,
                14, WEIGHT_SINCE_OCTOBER);
        server.send("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"get_activities","arguments":{}}}""");
        server.send("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"get_activity",\
                "arguments":{"id":24816092240}}}""");
        server.send("""
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"get_daily_summary",\
                "arguments":{"date":"2026-10-23"}}}""");
        server.send("""
                {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"get_sleep",\
                "arguments":{"date":"2026-10-23"}}}""");
        server.send("""
                {"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"get_weight",\
                "arguments":{"from":"2026-10-22","to":"2026-11-20"}}}""");
        // In whatever order they finish. Wait for all of them before closing stdin: the SDK drops the rest.
        var replies = new HashMap<Integer, String>();
        for (int i = 0; i < expected.size(); i++) {
            JsonNode reply = server.reply();
            assertThat(reply.path("result").path("isError").asBoolean()).as(reply.toString()).isFalse();
            replies.put(reply.path("id").asInt(), text(reply));
        }
        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        assertThat(replies).containsOnlyKeys(expected.keySet());
        expected.forEach((id, pinned) -> assertThat(replies.get(id)).as("reply %d", id).isEqualTo(compact(pinned)));
        // One profile lookup for all five: the display name and the time zone, asked for once each.
        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
        assertThat(garmin.requests(PERSONAL)).hasSize(1);
        assertOnlyJsonRpcOnStdout(server, 6);
        assertNoTokens(server);
    }

    @Test
    void logFileIsPrivate() throws Exception {
        var server = start("serve");
        server.send(INITIALIZE);
        server.reply();
        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        assertThat(Files.getPosixFilePermissions(logFile())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(logFile().getParent()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void endsWhenStdinClosesBeforeAnything() throws Exception {
        var server = start("serve");
        server.closeStdin();
        assertThat(server.exitCode()).isZero();
        assertThat(server.stdout()).isEmpty();
    }

    @Test
    void endsWithAnErrorRatherThanHangsOnALineThatIsNotJsonRpc() throws Exception {
        // The SDK stops reading on a line it can't parse. stdin stays open: serve must still end, and say why.
        var server = start("serve");
        server.send("this is not JSON");
        assertThat(server.exitCode()).isEqualTo(1);
        assertThat(server.stdout()).isEmpty();
        assertThat(server.stderr()).contains("couldn't read a message from the AI app", logFile().toString());
        assertThat(Files.readString(logFile())).contains("Error processing inbound message");
    }

    @Test
    void saysWhyWhenItCannotStart() throws Exception {
        Files.createDirectories(logFile());
        var server = start("serve");
        assertThat(server.exitCode()).isEqualTo(1);
        assertThat(server.stdout()).isEmpty();
        assertThat(server.stderr()).contains("stride-mcp couldn't start the MCP server:",
                "Couldn't prepare the log file " + logFile(), "not a plain file");
    }

    @Test
    void settingsMayComeFirst() throws Exception {
        var server = start("--spring.main.banner-mode=off", "serve");
        server.send(INITIALIZE);
        assertThat(server.reply().path("result").path("serverInfo").path("name").asString()).isEqualTo("stride-mcp");
        server.closeStdin();
        assertThat(server.exitCode()).isZero();
    }

    @Test
    void aWrongServeLineIsLeftToTheCommands() throws Exception {
        // The commands report it, as for any other wrong command line. No server, no log file.
        var server = start("serve", "extra");
        assertThat(server.exitCode()).isEqualTo(2);
        assertThat(server.stderr()).contains(StrideCommands.SERVE_ARGUMENTS, "Usage:");
        assertThat(server.stdout()).isEmpty();
        assertThat(logFile()).doesNotExist();
    }

    private Path logFile() {
        return temp.resolve("home/.stride-mcp/stride-mcp.log");
    }

    /**
     * {@code serve} with every Garmin address on {@link #garmin}, signed in with its fake tokens, and its clock
     * stopped at {@link com.hammez.stride.mcp.ToolFixtures#NOW}, as in the tool tests.
     */
    private Server startWithFakeGarmin() throws IOException {
        // Good for another hour at NOW, so no refresh.
        garmin.signedInApi(tokenFile(), Clock.fixed(NOW, ZoneOffset.UTC));
        return start("serve", "--spring.main.sources=" + FakeGarminServe.class.getName(),
                "--stride.test.fake-garmin=" + garmin.config().connectApiBaseUrl(),
                "--stride.test.now=" + NOW);
    }

    private Path tokenFile() {
        return temp.resolve("home/.stride-mcp/garmin_tokens.json");
    }

    /** Waits for the end of stdout, then checks it had exactly this many lines, each a JSON-RPC message. */
    private void assertOnlyJsonRpcOnStdout(Server server, int lines) throws InterruptedException {
        for (String line : server.stdout()) {
            assertThat(json.readTree(line).path("jsonrpc").asString()).as(line).isEqualTo("2.0");
        }
        assertThat(server.stdout()).hasSize(lines);
    }

    /** No fake token on stdout, stderr or in the log. Call once the server has ended. */
    private void assertNoTokens(Server server) throws Exception {
        for (String output : List.of(String.join("\n", server.stdout()), server.stderr(),
                Files.readString(logFile()))) {
            assertThat(output).doesNotContain(FakeGarmin.ACCESS_TOKEN).doesNotContain(FakeGarmin.REFRESH_TOKEN);
        }
    }

    /** A tools/call reply's one text. */
    private static String text(JsonNode reply) {
        JsonNode content = reply.path("result").path("content");
        assertThat(content).hasSize(1);
        return content.get(0).path("text").asString();
    }

    private Server start(String... args) throws IOException {
        Path home = temp.resolve("home");
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                // A proxy nothing listens on: any https call, and any http call off this computer, fails.
                "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=1",
                "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=1",
                "-cp", System.getProperty("java.class.path"),
                StrideMcpApplication.class.getName()));
        command.addAll(List.of(args));
        // Every file under the temp folder, even if a default changes.
        command.addAll(List.of("--stride.log-file=" + logFile(),
                "--stride.token-file=" + tokenFile(),
                "--stride.cache-dir=" + home.resolve(".stride-mcp/cache")));
        process = new ProcessBuilder(command).start();
        return new Server(process);
    }

    /** The server process, with its stdout read line by line as it comes and its stderr kept. */
    private final class Server {

        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> stdout = new ArrayList<>();
        private final Thread stdoutReader;
        private final CompletableFuture<String> stderr;

        Server(Process process) {
            this.process = process;
            this.stdin = process.getOutputStream();
            this.stdoutReader = Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // The process ended.
                }
            });
            this.stderr = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
        }

        void send(String message) throws IOException {
            stdin.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        JsonNode reply() throws InterruptedException {
            String line = lines.poll(30, TimeUnit.SECONDS);
            assertThat(line).as("a reply on stdout. stderr so far: %s", stderr.getNow("")).isNotNull();
            stdout.add(line);
            return json.readTree(line);
        }

        void closeStdin() throws IOException {
            stdin.close();
        }

        int exitCode() throws InterruptedException {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the server ended").isTrue();
            return process.exitValue();
        }

        /** Every stdout line. Call after {@link #exitCode()}: waits until stdout has been read to its end. */
        List<String> stdout() throws InterruptedException {
            assertThat(stdoutReader.join(Duration.ofSeconds(30))).as("stdout read to its end").isTrue();
            lines.drainTo(stdout);
            return stdout;
        }

        String stderr() {
            return stderr.join();
        }
    }
}
