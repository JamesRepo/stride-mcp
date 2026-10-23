package com.hammez.stride.garmin.auth;

import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fetches the user's Garmin profile. Used to check that tokens actually work. */
public class ProfileClient {

    private static final String STEP = "the profile check";

    private final GarminApi api;
    private final GarminAuthConfig config;
    private final JsonMapper json;

    public ProfileClient(GarminApi api, GarminAuthConfig config, JsonMapper json) {
        this.api = api;
        this.config = config;
        this.json = json;
    }

    /** The profile and the tokens that fetched it. */
    record SignedProfile(GarminProfile profile, GarminTokens tokens) {
    }

    /** With tokens that aren't saved yet (straight after login). A 401 means "Session expired". */
    GarminProfile fetch(String accessToken) {
        return parse(api.getWith(accessToken, STEP, config.profilePath()));
    }

    /** With the saved session, refreshing it if needed. */
    SignedProfile fetchSaved() {
        GarminApi.Signed reply = api.getSigned(STEP, config.profilePath(), Map.of());
        return new SignedProfile(parse(reply.body()), reply.tokens());
    }

    /**
     * Only a JSON object counts as a working profile check. An empty reply (HTTP 204) or a page that isn't
     * JSON (such as bot protection) is an error, so login never saves tokens that weren't shown to work.
     */
    private GarminProfile parse(String body) {
        JsonNode profile = GarminHttp.jsonBody(body, json);
        if (!profile.isObject()) {
            throw new GarminException("Garmin's reply to " + STEP + " wasn't a profile"
                    + (body.isBlank() ? " (it was empty)" : "") + ". Try again later. "
                    + "If it keeps happening, check for an update to stride-mcp.");
        }
        return new GarminProfile(
                profile.path("fullName").asString(null),
                profile.path("userName").asString(null),
                profile.path("displayName").asString(null));
    }
}
