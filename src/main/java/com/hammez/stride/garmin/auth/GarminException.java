package com.hammez.stride.garmin.auth;

import java.util.Optional;

/**
 * A problem logging in to or fetching from Garmin, with a message that tells the user what to do next.
 * Messages never contain tickets or tokens, or Garmin's replies: an HTTP error keeps Garmin's reply apart, in
 * {@link #garminReply()}.
 */
public class GarminException extends RuntimeException {

    private final String garminReply;

    public GarminException(String message) {
        super(message);
        this.garminReply = null;
    }

    public GarminException(String message, Throwable cause) {
        super(message, cause);
        this.garminReply = null;
    }

    /** An HTTP error, with Garmin's reply. Its secrets must already be hidden. */
    GarminException(String message, String garminReply) {
        super(message);
        this.garminReply = garminReply;
    }

    /**
     * Garmin's reply to the request that failed, with secrets hidden. The CLI shows it; the MCP tools never do
     * (Garmin's replies never reach the AI or the log).
     */
    public Optional<String> garminReply() {
        return Optional.ofNullable(garminReply);
    }
}
