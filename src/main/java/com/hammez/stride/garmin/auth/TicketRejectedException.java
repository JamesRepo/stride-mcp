package com.hammez.stride.garmin.auth;

/** Garmin refused the ticket (expired or already used). */
public class TicketRejectedException extends GarminException {

    public TicketRejectedException() {
        super("Garmin didn't accept that ticket. Run `stride-mcp login` again.");
    }
}
