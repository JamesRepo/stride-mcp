package com.hammez.stride.garmin.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/**
 * A fake Garmin on localhost. Queue responses per path, then check the requests it received.
 * Unqueued paths answer 500, so a test never silently passes on a missing response.
 */
final class FakeGarmin implements AutoCloseable {

    static final String TOKEN_PATH = "/di-oauth2-service/oauth/token";
    static final String PROFILE_PATH = "/userprofile-service/socialProfile";

    record Response(int status, String body) {
    }

    record Request(String method, String path, Headers headers, String body) {

        String header(String name) {
            return headers.getFirst(name);
        }

        Map<String, String> form() {
            var fields = new LinkedHashMap<String, String>();
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            return fields;
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    FakeGarmin() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestHeaders(), body));
            Deque<Response> queue = responses.get(path);
            Response response = queue == null || queue.isEmpty()
                    ? new Response(500, "{\"error\":\"no fake response queued\"}")
                    : queue.poll();
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

    FakeGarmin respond(String path, int status, String body) {
        responses.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Response(status, body));
        return this;
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    List<Request> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Real defaults (client IDs, headers), with every address pointing here. */
    GarminAuthConfig config() {
        return GarminAuthConfig.defaults().withBaseUrls(baseUrl(), baseUrl() + TOKEN_PATH, baseUrl());
    }

    HttpClient httpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** An unsigned JWT with the given claims, shaped like Garmin's access token. */
    static String jwt(long exp, String clientId) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"iat\":" + (exp - 75_600) + ",\"exp\":" + exp
                + ",\"client_id\":\"" + clientId + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".fake-signature";
    }

    /** A DI token reply, like Garmin's (lifetimes measured October 2026). */
    static String tokenReply(String accessToken, String refreshToken) {
        return """
                {"access_token":"%s","refresh_token":"%s","token_type":"Bearer","scope":"CONNECT_READ",\
                "expires_in":75600,"refresh_token_expires_in":2592000}""".formatted(accessToken, refreshToken);
    }
}
