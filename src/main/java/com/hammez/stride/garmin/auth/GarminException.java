package com.hammez.stride.garmin.auth;

/**
 * A problem logging in to or fetching from Garmin, with a message that tells the user what to do next.
 * Messages never contain tickets or tokens.
 */
public class GarminException extends RuntimeException {

    public GarminException(String message) {
        super(message);
    }

    public GarminException(String message, Throwable cause) {
        super(message, cause);
    }
}
