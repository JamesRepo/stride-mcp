package com.hammez.stride.garmin.auth;

/**
 * Garmin answered a data request ({@link GarminApi#get}) with an HTTP status stride-mcp has no meaning for, such as
 * 400 or 500. Not 401, 403, 404 or 429: those have their own messages (and, for 404 and 429, their own exceptions).
 * Login and token steps never throw it, even if a data request set off the token refresh that failed: those stay a
 * plain {@link GarminException}, so nothing mistakes them for a data reply.
 */
public class UnexpectedStatusException extends GarminException {

    private final int status;

    /** @param garminReply Garmin's reply, with its secrets already hidden */
    UnexpectedStatusException(String message, int status, String garminReply) {
        super(message, garminReply);
        this.status = status;
    }

    /** The HTTP status Garmin sent. */
    public int status() {
        return status;
    }
}
