package com.hammez.stride.garmin.auth;

/** Garmin refused every app client ID we know, or the one the saved tokens belong to. */
public class ClientIdsRejectedException extends GarminException {

    public ClientIdsRejectedException() {
        super("Garmin rejected the token swap. Its app IDs may have changed. Check for an update to stride-mcp.");
    }

    private ClientIdsRejectedException(String message) {
        super(message);
    }

    /** The refresh was turned down because of the app ID, not the refresh token. */
    static ClientIdsRejectedException onRefresh() {
        return new ClientIdsRejectedException("Garmin no longer accepts the app ID your saved session uses. "
                + "Its app IDs may have changed. Check for an update to stride-mcp, then run `stride-mcp login`.");
    }
}
