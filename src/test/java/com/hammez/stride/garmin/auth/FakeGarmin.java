package com.hammez.stride.garmin.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * A fake Garmin on localhost. Queue responses per path (and optionally per query), then check the requests
 * it received. Unqueued paths answer 500, so a test never silently passes on a missing response.
 * Answers each request on its own thread, so requests sent at once are answered at once.
 * Public so tests of the layers above {@code garmin.auth} can use it through {@link #signedInApi}.
 */
public final class FakeGarmin implements AutoCloseable {

    public static final String TOKEN_PATH = "/di-oauth2-service/oauth/token";
    public static final String PROFILE_PATH = "/userprofile-service/socialProfile";
    public static final String CLIENT_ID = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2";
    /** The tokens {@link #signedInApi} saves. Tests check they never show up anywhere. */
    public static final String ACCESS_TOKEN = jwt(4_000_000_000L, CLIENT_ID);
    public static final String REFRESH_TOKEN = "fake-refresh-token-0f3c";

    public record Response(int status, String body) {
    }

    /** @param query null to answer any query on the path */
    private record Queued(Map<String, String> query, Response response) {
    }

    /** @param query the raw query string, or null if there was none */
    public record Request(String method, String path, String query, Headers headers, String body) {

        public String header(String name) {
            return headers.getFirst(name);
        }

        Map<String, String> form() {
            return decode(body);
        }

        public Map<String, String> queryParams() {
            return query == null ? Map.of() : decode(query);
        }

        private static Map<String, String> decode(String pairs) {
            var fields = new LinkedHashMap<String, String>();
            for (String pair : pairs.split("&")) {
                int eq = pair.indexOf('=');
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            return fields;
        }
    }

    /** Requests to these paths wait until one to each has arrived. See {@link #together}. */
    private record Together(Set<String> waiting, CountDownLatch arrived) {
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Together together;
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final Map<String, Deque<Queued>> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    public FakeGarmin() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var request = new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders(), body);
            requests.add(request);
            pause(delays.get(path));
            Response response = arrivedTogether(path) ? next(path, request.queryParams())
                    : new Response(500, "{\"error\":\"not sent at the same time\"}");
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(threads);
        server.start();
    }

    /**
     * Holds the next request to each of these paths until one to every path has arrived, then answers them. If they
     * don't all arrive within 10 seconds, the held ones answer 500. So a test can show requests were sent at the same
     * time: sent one after another, the first would wait for a second that never comes.
     */
    public FakeGarmin together(String... paths) {
        Set<String> waiting = ConcurrentHashMap.newKeySet();
        waiting.addAll(List.of(paths));
        together = new Together(waiting, new CountDownLatch(paths.length));
        return this;
    }

    /**
     * Answers every request to this path only after a pause. So a test can be sure that calls let go together are
     * all waiting by the time the answer comes.
     */
    public FakeGarmin delay(String path, Duration pause) {
        delays.put(path, pause);
        return this;
    }

    private static void pause(Duration pause) {
        if (pause == null) {
            return;
        }
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** False if this request was held by {@link #together} and the others didn't come. */
    private boolean arrivedTogether(String path) {
        Together held = together;
        if (held == null || !held.waiting().remove(path)) {
            return true;
        }
        held.arrived().countDown();
        try {
            return held.arrived().await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Queues a response for the next request to this path (decoded, as Garmin sees it), whatever its query. */
    public FakeGarmin respond(String path, int status, String body) {
        return queue(path, null, status, body);
    }

    /** Queues a response for the next request to this path with exactly these query parameters. */
    public FakeGarmin respond(String path, Map<String, String> query, int status, String body) {
        return queue(path, Map.copyOf(query), status, body);
    }

    private FakeGarmin queue(String path, Map<String, String> query, int status, String body) {
        Deque<Queued> queue = responses.computeIfAbsent(path, p -> new ArrayDeque<>());
        // Requests may be taking from it on other threads (see next).
        synchronized (queue) {
            queue.add(new Queued(query, new Response(status, body)));
        }
        return this;
    }

    /** The first queued response that matches, taken off the queue. */
    private Response next(String path, Map<String, String> query) {
        Deque<Queued> queue = responses.get(path);
        if (queue != null) {
            synchronized (queue) {
                for (Iterator<Queued> it = queue.iterator(); it.hasNext(); ) {
                    Queued queued = it.next();
                    if (queued.query() == null || queued.query().equals(query)) {
                        it.remove();
                        return queued.response();
                    }
                }
            }
        }
        return new Response(500, "{\"error\":\"no fake response queued\"}");
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Real defaults (client IDs, headers), with every address pointing here. */
    public GarminAuthConfig config() {
        return GarminAuthConfig.defaults().withBaseUrls(baseUrl(), baseUrl() + TOKEN_PATH, baseUrl());
    }

    HttpClient httpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * A {@link GarminApi} pointed here, signed in with {@link #ACCESS_TOKEN} and {@link #REFRESH_TOKEN}
     * (good for another hour and day). A 401 makes it refresh at {@link #TOKEN_PATH}, as for real.
     */
    public GarminApi signedInApi(Path tokenFile, Clock clock) {
        JsonMapper json = JsonMapper.builder().build();
        var store = new TokenStore(tokenFile, json);
        store.save(new GarminTokens(ACCESS_TOKEN, REFRESH_TOKEN, CLIENT_ID,
                clock.instant().plusSeconds(3600), clock.instant().plusSeconds(86_400)));
        GarminAuthConfig config = config();
        var tokenClient = new DiTokenClient(httpClient(), config, json, clock);
        return new GarminApi(httpClient(), config, new TokenManager(store, tokenClient, config, clock));
    }

    @Override
    public void close() {
        // Let go of any request still held by together(), rather than wait out its 10 seconds.
        Together held = together;
        if (held != null) {
            while (held.arrived().getCount() > 0) {
                held.arrived().countDown();
            }
        }
        server.stop(0);
        threads.close();
    }

    /** An unsigned JWT with the given claims, shaped like Garmin's access token. */
    public static String jwt(long exp, String clientId) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"iat\":" + (exp - 75_600) + ",\"exp\":" + exp
                + ",\"client_id\":\"" + clientId + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".fake-signature";
    }

    /** A DI token reply, like Garmin's (lifetimes measured October 2026). */
    public static String tokenReply(String accessToken, String refreshToken) {
        return """
                {"access_token":"%s","refresh_token":"%s","token_type":"Bearer","scope":"CONNECT_READ",\
                "expires_in":75600,"refresh_token_expires_in":2592000}""".formatted(accessToken, refreshToken);
    }
}
