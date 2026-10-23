package com.hammez.stride.garmin;

import com.hammez.stride.garmin.auth.GarminException;

/** A reply from Garmin isn't the shape stride-mcp expects, most likely because Garmin changed it. */
public class UnexpectedReplyException extends GarminException {

    /** @param what the reply, for a reader: "activity 123", "the daily summary for 2026-10-22" */
    public UnexpectedReplyException(String what) {
        super("Garmin's reply for " + what + " wasn't what stride-mcp expected. Garmin may have changed it. "
                + "Check for an update to stride-mcp.");
    }
}
