package com.hammez.stride.garmin.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fetches the user's Garmin profile. Used to check that tokens actually work. */
public class ProfileClient {

    private final HttpClient http;
    private final GarminAuthConfig config;
    private final JsonMapper json;

    public ProfileClient(HttpClient http, GarminAuthConfig config, JsonMapper json) {
        this.http = http;
        this.config = config;
        this.json = json;
    }

    GarminProfile fetch(String accessToken) {
        var request = HttpRequest.newBuilder(URI.create(config.profileUrl()))
                .timeout(config.timeout())
                .GET();
        config.headersWith(Map.of(
                "Authorization", "Bearer " + accessToken,
                "Accept", "application/json")).forEach(request::header);

        HttpResponse<String> response = GarminHttp.send(http, request.build());
        int status = response.statusCode();
        if (status == 429) {
            throw new RateLimitedException();
        }
        if (status == 401) {
            throw new SessionExpiredException();
        }
        if (status == 403) {
            throw GarminHttp.forbidden("the profile check");
        }
        if (status != 200) {
            throw GarminHttp.unexpected("the profile check", response);
        }
        JsonNode body = GarminHttp.jsonBody(response, json);
        return new GarminProfile(
                body.path("fullName").asString(null),
                body.path("userName").asString(null),
                body.path("displayName").asString(null));
    }
}
