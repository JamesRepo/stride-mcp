package com.hammez.stride.garmin.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads claims from Garmin's access token. The signature isn't checked: we only use the claims
 * to decide when to refresh and which client ID to refresh with.
 */
record Jwt(Optional<Instant> expiresAt, Optional<String> clientId) {

    private static final Jwt EMPTY = new Jwt(Optional.empty(), Optional.empty());

    static Jwt parse(String token, JsonMapper json) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return EMPTY;
        }
        try {
            JsonNode claims = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            JsonNode exp = claims.path("exp");
            JsonNode clientId = claims.path("client_id");
            return new Jwt(
                    exp.isNumber() ? Optional.of(Instant.ofEpochSecond(exp.asLong())) : Optional.empty(),
                    clientId.isString() && !clientId.asString().isBlank()
                            ? Optional.of(clientId.asString()) : Optional.empty());
        } catch (IllegalArgumentException | JacksonException e) {
            return EMPTY;
        }
    }
}
