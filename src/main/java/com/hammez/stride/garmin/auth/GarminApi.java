package com.hammez.stride.garmin.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signed-in GET requests to Garmin Connect's API. Adds the saved session itself, so callers outside
 * {@code garmin.auth} never see a token. If Garmin turns down an access token that should still be good,
 * refreshes once and tries once more. No other retries, and never two refreshes for one request.
 */
public class GarminApi {

    private final HttpClient http;
    private final GarminAuthConfig config;
    private final TokenManager tokenManager;

    public GarminApi(HttpClient http, GarminAuthConfig config, TokenManager tokenManager) {
        this.http = http;
        this.config = config;
        this.tokenManager = tokenManager;
    }

    /** A reply body plus the tokens that got it, which may have just been refreshed. */
    record Signed(String body, GarminTokens tokens) {
    }

    /**
     * GETs a path on Garmin Connect's API with the saved session.
     *
     * @param path  starts with {@code /}. Encode any part filled in from data with {@link #segment(String)}.
     * @param query query parameters, unencoded
     * @return the body, or "" if Garmin had no data (HTTP 204)
     * @throws NotLoggedInException    if there's no saved session
     * @throws SessionExpiredException if the session has run out, or Garmin still says no after a refresh
     * @throws NotFoundException       on HTTP 404
     * @throws RateLimitedException    on HTTP 429
     * @throws GarminException         on HTTP 403, any other error, no connection, or a path that isn't plain
     */
    public String get(String path, Map<String, String> query) {
        return getSigned("the request to " + path, path, query).body();
    }

    /**
     * Percent-encodes one part of a path, such as the display name in {@code /…/daily/{displayName}}.
     * A value that would decode to {@code .} or {@code ..} between slashes is still refused by {@link #get}.
     */
    public static String segment(String value) {
        return encode(value);
    }

    Signed getSigned(String step, String path, Map<String, String> query) {
        URI uri = uri(path, query);
        TokenManager.Checked checked = tokenManager.checkedTokens();
        GarminTokens tokens = checked.tokens();
        HttpResponse<String> response = send(uri, tokens.accessToken());
        if (response.statusCode() != 401) {
            return new Signed(body(step, path, response, tokens.accessToken()), tokens);
        }
        // Tokens we refreshed a moment ago being turned down won't be fixed by refreshing again.
        if (checked.refreshed()) {
            throw new SessionExpiredException();
        }
        GarminTokens rejected = tokens;
        tokens = tokenManager.refreshRejected(rejected);
        response = send(uri, tokens.accessToken());
        return new Signed(body(step, path, response, tokens.accessToken(), rejected.accessToken()), tokens);
    }

    /** One GET with this access token, and no refresh. Login uses it to check tokens before saving them. */
    String getWith(String accessToken, String step, String path) {
        return body(step, path, send(uri(path, Map.of()), accessToken), accessToken);
    }

    private HttpResponse<String> send(URI uri, String accessToken) {
        var request = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .GET();
        config.headersWith(Map.of(
                "Authorization", "Bearer " + accessToken,
                "Accept", "application/json")).forEach(request::header);
        return GarminHttp.send(http, request.build());
    }

    /** @param accessTokens every access token sent for this request, hidden from any error text */
    private static String body(String step, String path, HttpResponse<String> response, String... accessTokens) {
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        if (status == 204) {
            return "";
        }
        if (status == 401) {
            throw new SessionExpiredException();
        }
        if (status == 403) {
            throw GarminHttp.forbidden(step);
        }
        if (status == 404) {
            throw new NotFoundException("Garmin has nothing at " + path + " (HTTP 404).");
        }
        if (status == 429) {
            throw new RateLimitedException();
        }
        throw GarminHttp.unexpected(step, response, accessTokens);
    }

    /** The full address. Query keys are sorted so the same request always has the same address. */
    private URI uri(String path, Map<String, String> query) {
        checkPath(path);
        var url = new StringBuilder(config.connectApiBaseUrl()).append(path);
        char separator = '?';
        for (var param : new TreeMap<>(query).entrySet()) {
            url.append(separator).append(encode(param.getKey())).append('=').append(encode(param.getValue()));
            separator = '&';
        }
        return URI.create(url.toString());
    }

    /** Percent-encoding (a space is {@code %20}), the same for path parts and query values. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Paths come from our own code, but the parts filled in from data (ids, display name) must not change
     * which address is called. This is strict on purpose: it checks the decoded path, so even an encoded
     * {@code %2F..} is refused, in case something between us and Garmin decodes it.
     */
    private static void checkPath(String path) {
        boolean plain = path.startsWith("/") && !path.contains("?") && !path.contains("#") && !path.contains("\\");
        if (plain) {
            try {
                for (String part : URLDecoder.decode(path, StandardCharsets.UTF_8).split("/")) {
                    if (part.equals(".") || part.equals("..")) {
                        plain = false;
                    }
                }
            } catch (IllegalArgumentException e) {
                plain = false;
            }
        }
        if (!plain) {
            throw new GarminException("stride-mcp built a Garmin address it won't use (" + path + "). "
                    + "This is a bug or an odd value from Garmin. Check for an update to stride-mcp.");
        }
    }
}
