package com.hammez.stride.garmin.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to Garmin's DI OAuth service: swaps a login ticket for tokens, and refreshes tokens.
 * Follows python-garminconnect's flow (re-implemented, not copied).
 */
public class DiTokenClient {

    /**
     * OAuth error codes meaning "this client ID isn't accepted", so the next one is worth trying.
     * Assumed from the OAuth standard; to be confirmed at the live check (October 2026).
     */
    private static final Set<String> CLIENT_ERRORS = Set.of("invalid_client", "unauthorized_client");

    private final HttpClient http;
    private final GarminAuthConfig config;
    private final JsonMapper json;
    private final Clock clock;

    public DiTokenClient(HttpClient http, GarminAuthConfig config, JsonMapper json, Clock clock) {
        this.http = http;
        this.config = config;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Swaps a single-use login ticket for tokens. Tries each known client ID once, in order.
     *
     * @throws TicketRejectedException     if Garmin says the ticket is bad or used
     * @throws ClientIdsRejectedException  if no client ID was accepted
     * @throws RateLimitedException        on HTTP 429
     */
    public GarminTokens exchangeTicket(String ticket) {
        for (String clientId : config.clientIds()) {
            var fields = new LinkedHashMap<String, String>();
            fields.put("client_id", clientId);
            fields.put("service_ticket", ticket);
            fields.put("grant_type", config.serviceTicketGrantType());
            fields.put("service_url", config.serviceUrl());

            HttpResponse<String> response = post(clientId, "application/json,text/html;q=0.9,*/*;q=0.8", fields);
            int status = response.statusCode();
            if (status == 200) {
                return tokensFrom(response, clientId);
            }
            if (status == 429) {
                throw new RateLimitedException();
            }
            if (status == 403) {
                throw GarminHttp.forbidden("the token swap");
            }
            if (status >= 500) {
                throw GarminHttp.unexpected("the token swap", response, ticket);
            }
            String error = GarminHttp.oauthError(response, json);
            if (error.equals("invalid_grant")) {
                throw new TicketRejectedException();
            }
            if (CLIENT_ERRORS.contains(error)) {
                continue; // This client ID isn't accepted: try the next one.
            }
            // Anything else would fail the same way for every client ID, so don't resend the ticket.
            if (status == 400 || status == 401) {
                throw GarminHttp.rejected("the token swap", response, ticket);
            }
            throw GarminHttp.unexpected("the token swap", response, ticket);
        }
        throw new ClientIdsRejectedException();
    }

    /**
     * Gets a new access token and a new refresh token. The old refresh token may stop working,
     * so save the result straight away.
     *
     * @throws SessionExpiredException     if the refresh token is rejected
     * @throws ClientIdsRejectedException if the tokens' client ID is no longer accepted
     * @throws RateLimitedException       on HTTP 429
     */
    public GarminTokens refresh(GarminTokens current) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("grant_type", "refresh_token");
        fields.put("client_id", current.clientId());
        fields.put("refresh_token", current.refreshToken());

        HttpResponse<String> response = post(current.clientId(), "application/json", fields);
        int status = response.statusCode();
        if (status == 200) {
            return tokensFrom(response, current.clientId());
        }
        if (status == 429) {
            throw new RateLimitedException();
        }
        if (status == 403) {
            throw GarminHttp.forbidden("the token refresh");
        }
        String error = GarminHttp.oauthError(response, json);
        if (CLIENT_ERRORS.contains(error)) {
            throw ClientIdsRejectedException.onRefresh();
        }
        if (status == 400 || status == 401) {
            // invalid_grant, or no error code at all: the refresh token itself is bad or has run out.
            if (error.equals("invalid_grant") || error.isEmpty()) {
                throw new SessionExpiredException();
            }
            throw GarminHttp.rejected("the token refresh", response, current.refreshToken());
        }
        throw GarminHttp.unexpected("the token refresh", response, current.refreshToken());
    }

    private HttpResponse<String> post(String clientId, String accept, Map<String, String> fields) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(config.diTokenUrl()))
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(GarminHttp.form(fields)));
        config.headersWith(Map.of(
                "Authorization", "Basic " + basic,
                "Accept", accept,
                "Content-Type", "application/x-www-form-urlencoded",
                "Cache-Control", "no-cache")).forEach(request::header);
        return GarminHttp.send(http, request.build());
    }

    private GarminTokens tokensFrom(HttpResponse<String> response, String clientId) {
        JsonNode body = GarminHttp.jsonBody(response, json);
        String accessToken = body.path("access_token").asString("");
        String refreshToken = body.path("refresh_token").asString("");
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw new GarminAuthException("Garmin's token reply was missing a token. "
                    + "Its login may have changed. Check for an update to stride-mcp.");
        }
        Instant now = clock.instant();
        Jwt jwt = Jwt.parse(accessToken, json);

        Instant accessExpiry = jwt.expiresAt().orElseGet(() -> {
            JsonNode expiresIn = body.path("expires_in");
            if (!expiresIn.isNumber()) {
                throw new GarminAuthException("Garmin's token reply had no expiry time. "
                        + "Its login may have changed. Check for an update to stride-mcp.");
            }
            return now.plusSeconds(expiresIn.asLong());
        });
        // Each reply carries a new refresh token. If Garmin doesn't say how long it lasts, save "unknown"
        // rather than the old token's expiry, and let Garmin decide when it's next used.
        JsonNode refreshExpiresIn = body.path("refresh_token_expires_in");
        Instant refreshExpiry = refreshExpiresIn.isNumber() ? now.plusSeconds(refreshExpiresIn.asLong()) : null;

        return new GarminTokens(accessToken, refreshToken, jwt.clientId().orElse(clientId),
                accessExpiry, refreshExpiry);
    }
}
