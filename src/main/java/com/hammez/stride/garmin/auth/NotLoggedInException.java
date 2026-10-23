package com.hammez.stride.garmin.auth;

/** There is no saved token file. */
public class NotLoggedInException extends GarminException {

    public NotLoggedInException() {
        super("Not logged in. Run `stride-mcp login`.");
    }
}
