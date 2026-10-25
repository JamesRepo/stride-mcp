package com.hammez.stride;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hammez.stride.cli.StrideCommands;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code serve} as an AI app runs it: a separate Java process, talked to over its stdin and stdout. Nothing here
 * talks to Garmin: there are no tools yet, and the token file doesn't exist.
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
    private Process process;

    @AfterEach
    void stop() {
        if (process != null) {
            process.destroyForcibly();
        }
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
        assertThat(toolsList.path("result").path("tools").isArray()).isTrue();

        server.closeStdin();
        assertThat(server.exitCode()).isZero();

        // Every line on stdout was a JSON-RPC message. The stray println went to stderr, the warning to the log.
        for (String line : server.stdout()) {
            assertThat(json.readTree(line).path("jsonrpc").asString()).as(line).isEqualTo("2.0");
        }
        assertThat(server.stdout()).hasSize(2);
        assertThat(server.stderr()).contains(NoisyStartup.PRINTED).doesNotContain(NoisyStartup.LOGGED);
        assertThat(Files.readString(logFile())).contains(NoisyStartup.LOGGED);
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

    private Server start(String... args) throws IOException {
        Path home = temp.resolve("home");
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                StrideMcpApplication.class.getName()));
        command.addAll(List.of(args));
        // Every file under the temp folder, even if a default changes.
        command.addAll(List.of("--stride.log-file=" + logFile(),
                "--stride.token-file=" + home.resolve(".stride-mcp/garmin_tokens.json"),
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
