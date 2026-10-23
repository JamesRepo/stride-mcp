package com.hammez.stride.garmin.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends one request to Garmin. No retries: callers decide what a failure means. */
final class GarminHttp {

    private GarminHttp() {
    }

    static HttpResponse<String> send(HttpClient http, HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GarminException("Couldn't reach Garmin (" + e.getClass().getSimpleName()
                    + "). Check your internet connection and try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GarminException("Interrupted while waiting for Garmin.", e);
        }
    }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** The JSON body, or a missing node if it isn't JSON. */
    static JsonNode jsonBody(HttpResponse<String> response, JsonMapper json) {
        return jsonBody(response.body(), json);
    }

    /** The JSON in a body, or a missing node if it isn't JSON. */
    static JsonNode jsonBody(String body, JsonMapper json) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return json.missingNode();
        }
    }

    /** The OAuth {@code error} code in an error body, or "" if there is none. */
    static String oauthError(HttpResponse<String> response, JsonMapper json) {
        return jsonBody(response, json).path("error").asString("");
    }

    /** HTTP 403: Garmin (or its bot protection) refused us. Never worth retrying straight away. */
    static GarminException forbidden(String step) {
        return new GarminException("Garmin refused the request during " + step + " (HTTP 403). "
                + "It may be blocking this connection. Wait a while before trying again.");
    }

    static GarminException unexpected(String step, HttpResponse<String> response, String... secrets) {
        return new GarminException("Garmin returned HTTP " + response.statusCode() + " during " + step
                + ". Try again later. Response: " + Redactor.redact(response.body(), secrets));
    }

    /** HTTP 400 or 401 with an error we don't recognise. Logging in again is the likely fix. */
    static GarminException rejected(String step, HttpResponse<String> response, String... secrets) {
        return new GarminException("Garmin rejected " + step + " (HTTP " + response.statusCode() + "). "
                + "Run `stride-mcp login` again. If it keeps failing, check for an update to stride-mcp. "
                + "Response: " + Redactor.redact(response.body(), secrets));
    }
}
