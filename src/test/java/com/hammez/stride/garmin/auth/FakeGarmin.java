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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * A fake Garmin on localhost. Queue responses per path (and optionally per query), then check the requests
 * it received. Unqueued paths answer 500, so a test never silently passes on a missing response.
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

    private final HttpServer server;
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
            Response response = next(path, request.queryParams());
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
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
        responses.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Queued(query, new Response(status, body)));
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
        server.stop(0);
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
