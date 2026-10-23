package com.hammez.stride.garmin.auth;

/** Garmin answered HTTP 404: there's nothing at that address, such as an activity id that doesn't exist. */
public class NotFoundException extends GarminException {

    public NotFoundException(String message) {
        super(message);
    }
}
