package com.hammez.stride.garmin.auth;

/**
 * The few profile fields {@code whoami} shows.
 *
 * @param displayName Garmin's internal ID (a UUID), shown only if nothing better exists
 */
public record GarminProfile(String fullName, String userName, String displayName) {

    /** The most human-friendly name available. */
    public String friendlyName() {
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        if (userName != null && !userName.isBlank()) {
            return userName;
        }
        return displayName != null && !displayName.isBlank() ? displayName : "(unknown)";
    }
}
