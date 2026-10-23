package com.hammez.stride.garmin.auth;

/** Garmin answered HTTP 429. */
public class RateLimitedException extends GarminAuthException {

    public RateLimitedException() {
        super("Garmin is rate limiting. Wait a while (an hour or more) before trying again.");
    }
}
