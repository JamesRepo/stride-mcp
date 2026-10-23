package com.hammez.stride.garmin.auth;

/** The pasted text had no {@code ST-…} ticket in it. */
public class NoTicketFoundException extends GarminAuthException {

    public NoTicketFoundException() {
        super("No ticket found. Copy the full address from the browser's address bar, then run `stride-mcp login` again.");
    }
}
