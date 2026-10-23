package com.hammez.stride.garmin.auth;

/**
 * A login or token problem, with a message that tells the user what to do next.
 * Messages never contain tickets or tokens.
 */
public class GarminAuthException extends RuntimeException {

    public GarminAuthException(String message) {
        super(message);
    }

    public GarminAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
