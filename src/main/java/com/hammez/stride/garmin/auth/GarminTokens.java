package com.hammez.stride.garmin.auth;

import java.time.Instant;

/**
 * The saved Garmin session. This is all the token file holds.
 *
 * @param clientId              the app client ID the tokens belong to (refresh must use it)
 * @param refreshTokenExpiresAt null if Garmin didn't say
 */
public record GarminTokens(
        String accessToken,
        String refreshToken,
        String clientId,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt) {

    /** Never prints the tokens themselves. */
    @Override
    public String toString() {
        return "GarminTokens[clientId=" + clientId
                + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + ", tokens=<redacted>]";
    }
}
