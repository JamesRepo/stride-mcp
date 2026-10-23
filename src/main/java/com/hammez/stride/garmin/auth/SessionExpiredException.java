package com.hammez.stride.garmin.auth;

/** The refresh token was rejected or has run out. */
public class SessionExpiredException extends GarminException {

    public SessionExpiredException() {
        super("Session expired. Run `stride-mcp login`.");
    }
}
