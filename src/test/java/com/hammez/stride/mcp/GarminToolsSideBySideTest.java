package com.hammez.stride.mcp;

import static com.hammez.stride.garmin.auth.FakeGarmin.PROFILE_PATH;
import static com.hammez.stride.garmin.auth.FakeGarmin.TOKEN_PATH;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITIES;
import static com.hammez.stride.mcp.ToolFixtures.ACTIVITY;
import static com.hammez.stride.mcp.ToolFixtures.LOG_FILE;
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
import static com.hammez.stride.mcp.ToolFixtures.compact;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import com.hammez.stride.garmin.Fixtures;
import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminDataConfig;
import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminTokens;
import com.hammez.stride.garmin.auth.TokenStore;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools called at once, as an AI app may: the MCP SDK runs each {@code tools/call} on a thread of its own (see
 * "Settled in step 6" in docs/phases/phase-3-mcp.md). On one client, as in a running server, with a fake Garmin.
 * <p>
 * The replies must be the ones {@link GarminToolsTest} pins for the same calls made one at a time. Repeated, since
 * a race may not show every time. When the token refresh or the profile lookup fails, every call gets its error,
 * and Garmin is still asked once: the calls that were waiting don't each try again.
 */
class GarminToolsSideBySideTest {

    private static final String NEW_ACCESS_TOKEN = FakeGarmin.jwt(NOW.getEpochSecond() + 75_600, FakeGarmin.CLIENT_ID);
    private static final String NEW_REFRESH_TOKEN = "fake-refresh-token-9a1d";

    private record Call(String tool, Map<String, Object> arguments, String reply) {
    }

    /**
     * Calls that look up the profile before anything else, two of each tool. get_activity is left out: it asks for
     * the activity first.
     */
    private static final List<Call> PROFILE_FIRST = Stream.of(
            new Call("get_activities", Map.of(), null),
            new Call("get_daily_summary", Map.of("date", "2026-10-23"), null),
            new Call("get_sleep", Map.of("date", "2026-10-23"), null))
            .flatMap(call -> Stream.of(call, call))
            .toList();

    /** What Garmin gets wrong while the calls wait, and what each call says then. */
    enum Failure {
        PROFILE_RATE_LIMITED(PROFILE_PATH, 429, "",
                "Garmin is rate limiting. Wait a while (an hour or more) before trying again."),
        PROFILE_FORBIDDEN(PROFILE_PATH, 403, "<html>blocked</html>",
                "Garmin refused the request during the request to " + PROFILE_PATH
                        + " (HTTP 403). It may be blocking this connection. Wait a while before trying again."),
        REFRESH_RATE_LIMITED(TOKEN_PATH, 429, "",
                "Garmin is rate limiting. Wait a while (an hour or more) before trying again."),
        REFRESH_REJECTED(TOKEN_PATH, 400, "{\"error\":\"invalid_grant\"}",
                "Session expired. Run `stride-mcp login`.");

        final String path;
        final int status;
        final String body;
        final String message;

        Failure(String path, int status, String body, String message) {
            this.path = path;
            this.status = status;
            this.body = body;
            this.message = message;
        }
    }

    /** All four tools, each twice: two calls for the same day or activity at once. */
    private static final List<Call> CALLS = Stream.of(
            new Call("get_activities", Map.of(), LAST_SEVEN_DAYS),
            new Call("get_activity", Map.of("id", 24816092240L), STAIR_STEPPER),
            new Call("get_daily_summary", Map.of("date", "2026-10-23"), DAY_WITH_TWO_WEIGH_INS),
            new Call("get_sleep", Map.of("date", "2026-10-23"), LAST_NIGHT))
            .flatMap(call -> Stream.of(call, call))
            .toList();

    @TempDir
    Path temp;

    private final FakeGarmin garmin = new FakeGarmin();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void stopGarmin() {
        garmin.close();
    }

    @RepeatedTest(5)
    void callsAtOnceGiveTheSameRepliesWithOneRefreshAndOneProfileLookup() throws Exception {
        // The access token has 5 minutes left, inside the 15-minute margin: the first call must refresh it.
        List<SyncToolSpecification> tools = tools(NOW.plus(Duration.ofMinutes(5)));
        var store = new TokenStore(temp.resolve("garmin_tokens.json"), json);
        Path cacheDir = temp.resolve("cache");

        // One refresh and one profile lookup queued: a second would get the fake's 500 and fail its call.
        garmin.respond(TOKEN_PATH, 200, FakeGarmin.tokenReply(NEW_ACCESS_TOKEN, NEW_REFRESH_TOKEN))
                .respond(PROFILE_PATH, 200, Fixtures.read("social-profile.json"))
                .respond(PERSONAL, 200, Fixtures.read("personal-information.json"));
        // Data for every call: two calls for the same thing may both miss the cache.
        for (int i = 0; i < 2; i++) {
            garmin.respond(ACTIVITIES, 200, Fixtures.read("activities.json"))
                    .respond(ACTIVITY, 200, Fixtures.read("activity.json"))
                    .respond(SPLITS, 200, Fixtures.read("activity-splits.json"))
                    .respond(SUMMARY, 200, Fixtures.read("summary-today.json"))
                    .respond(WEIGHT_PATH + "2026-10-23", 200, Fixtures.TWO_WEIGH_INS)
                    .respond(SLEEP, 200, Fixtures.read("sleep-last-night.json"));
        }
        // Proof they really ran at once: Garmin holds a summary and a sleep request until both have arrived.
        garmin.together(SUMMARY, SLEEP);

        List<CallToolResult> replies = callAtOnce(tools, CALLS);

        for (int i = 0; i < CALLS.size(); i++) {
            assertThat(isError(replies.get(i))).isFalse();
            assertThat(text(replies.get(i))).as(CALLS.get(i).toString()).isEqualTo(compact(CALLS.get(i).reply()));
        }
        assertThat(garmin.requests(TOKEN_PATH)).hasSize(1);
        assertThat(store.load().orElseThrow().accessToken()).isEqualTo(NEW_ACCESS_TOKEN);
        // Every data request waited for the refresh and used the new token.
        assertThat(garmin.requests()).filteredOn(request -> !request.path().equals(TOKEN_PATH)).isNotEmpty()
                .allSatisfy(request -> assertThat(request.header("Authorization"))
                        .isEqualTo("Bearer " + NEW_ACCESS_TOKEN));
        assertThat(garmin.requests(PROFILE_PATH)).hasSize(1);
        assertThat(garmin.requests(PERSONAL)).hasSize(1);
        // Each cache file is one whole reply, and no temp file is left behind.
        assertThat(cachedBody(cacheDir, "summary-2026-10-23")).isEqualTo(Fixtures.read("summary-today.json"));
        assertThat(cachedBody(cacheDir, "sleep-2026-10-23")).isEqualTo(Fixtures.read("sleep-last-night.json"));
        assertThat(cachedBody(cacheDir, "weight-2026-10-23")).isEqualTo(Fixtures.TWO_WEIGH_INS);
        assertThat(cachedBody(cacheDir, "activity-24816092240")).isEqualTo(Fixtures.read("activity.json"));
        try (Stream<Path> files = Files.list(cacheDir)) {
            assertThat(files.map(file -> file.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
        assertNoTokens(replies);
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void whenGarminFailsTheCallsWaitingShareTheErrorAndDontAskAgain(Failure failure) throws Exception {
        // A refresh is needed only for the refresh failures. Otherwise the tokens are good for another hour.
        boolean refreshFails = failure.path.equals(TOKEN_PATH);
        List<SyncToolSpecification> tools = tools(NOW.plus(refreshFails ? Duration.ofMinutes(5) : Duration.ofHours(1)));
        // One answer only: a call that asked again would get the fake's 500, with another message. The pause makes
        // sure every call is waiting by the time the failure comes back.
        garmin.respond(failure.path, failure.status, failure.body).delay(failure.path, Duration.ofMillis(300));

        List<CallToolResult> results = callAtOnce(tools, PROFILE_FIRST);

        for (int i = 0; i < PROFILE_FIRST.size(); i++) {
            assertThat(isError(results.get(i))).as(PROFILE_FIRST.get(i).toString()).isTrue();
            assertThat(text(results.get(i))).as(PROFILE_FIRST.get(i).toString()).isEqualTo(failure.message);
        }
        // Asked once, and nothing after it.
        assertThat(garmin.requests()).singleElement().satisfies(request ->
                assertThat(request.path()).isEqualTo(failure.path));
        assertNoTokens(results);
    }

    @RepeatedTest(3)
    void notLoggedInIsEachCallsErrorWithNothingSent() throws Exception {
        List<SyncToolSpecification> tools = tools(NOW.plus(Duration.ofHours(1)));
        Files.delete(temp.resolve("garmin_tokens.json"));

        List<CallToolResult> results = callAtOnce(tools, CALLS);

        for (CallToolResult result : results) {
            assertThat(isError(result)).isTrue();
            assertThat(text(result)).isEqualTo("Not logged in. Run `stride-mcp login`.");
        }
        assertThat(garmin.requests()).isEmpty();
    }

    /**
     * The tools on one client, as in a running server, signed in with the fake's tokens, whose access token expires
     * at {@code accessTokenExpiresAt}.
     */
    private List<SyncToolSpecification> tools(Instant accessTokenExpiresAt) {
        Path tokenFile = temp.resolve("garmin_tokens.json");
        var api = garmin.signedInApi(tokenFile, clock);
        new TokenStore(tokenFile, json).save(new GarminTokens(FakeGarmin.ACCESS_TOKEN, FakeGarmin.REFRESH_TOKEN,
                FakeGarmin.CLIENT_ID, accessTokenExpiresAt, NOW.plus(Duration.ofDays(1))));
        var client = new GarminClient(api, new GarminCache(temp.resolve("cache"), json, clock),
                GarminDataConfig.defaults(garmin.config()), json, clock, ZoneOffset.UTC);
        return new SyncMcpToolProvider(List.of(new GarminTools(client, LOG_FILE))).getToolSpecifications();
    }

    /** Every call on its own thread, all let go at the same moment. The results, in the order of the calls. */
    private List<CallToolResult> callAtOnce(List<SyncToolSpecification> tools, List<Call> calls) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<CallToolResult>>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Call call : calls) {
                SyncToolSpecification spec = tools.stream().filter(s -> s.tool().name().equals(call.tool()))
                        .findFirst().orElseThrow();
                futures.add(threads.submit(() -> {
                    start.await();
                    return spec.callHandler().apply(null, new CallToolRequest(call.tool(), call.arguments(), null));
                }));
            }
            start.countDown();
            var results = new ArrayList<CallToolResult>();
            for (Future<CallToolResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static boolean isError(CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }

    /** The result's one text. */
    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }

    private static void assertNoTokens(List<CallToolResult> results) {
        String all = String.join("\n", results.stream().map(GarminToolsSideBySideTest::text).toList());
        assertThat(all).doesNotContain(FakeGarmin.ACCESS_TOKEN, FakeGarmin.REFRESH_TOKEN, NEW_ACCESS_TOKEN,
                NEW_REFRESH_TOKEN);
    }

    /** The reply saved in a cache file, which must be whole JSON. */
    private String cachedBody(Path cacheDir, String name) throws Exception {
        return json.readTree(Files.readString(cacheDir.resolve(name + ".json"))).path("body").asString();
    }
}
