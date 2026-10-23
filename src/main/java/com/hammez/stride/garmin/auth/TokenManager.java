package com.hammez.stride.garmin.auth;

import java.time.Clock;
import java.time.Instant;

/**
 * Hands out working tokens, refreshing them shortly before they expire. Each load, refresh and save
 * happens under the token store's lock, so separate stride-mcp processes can't refresh at once.
 */
public class TokenManager {

    private final TokenStore store;
    private final DiTokenClient tokenClient;
    private final GarminAuthConfig config;
    private final Clock clock;

    public TokenManager(TokenStore store, DiTokenClient tokenClient, GarminAuthConfig config, Clock clock) {
        this.store = store;
        this.tokenClient = tokenClient;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Saved tokens whose access token has at least the refresh margin (15 minutes) left.
     * Refreshes and saves first if needed.
     *
     * @throws NotLoggedInException    if there's no token file
     * @throws SessionExpiredException if the refresh token has run out or is rejected
     */
    public GarminTokens validTokens() {
        return checkedTokens().tokens();
    }

    /** Tokens from {@link #validTokens()}, and whether getting them took a refresh. */
    record Checked(GarminTokens tokens, boolean refreshed) {
    }

    /** As {@link #validTokens()}, but also says whether it just refreshed. */
    Checked checkedTokens() {
        return store.underLock(() -> {
            GarminTokens tokens = store.load().orElseThrow(NotLoggedInException::new);
            if (tokens.accessTokenExpiresAt().isAfter(clock.instant().plus(config.refreshMargin()))) {
                return new Checked(tokens, false);
            }
            return new Checked(refreshAndSave(tokens), true);
        });
    }

    /**
     * Garmin turned down this access token before we expected (revoked early, or our clock is off).
     * Refreshes once, unless another process has already saved newer tokens.
     *
     * @throws NotLoggedInException    if the token file has gone
     * @throws SessionExpiredException if the refresh token has run out or is rejected
     */
    public GarminTokens refreshRejected(GarminTokens rejected) {
        return store.underLock(() -> {
            GarminTokens tokens = store.load().orElseThrow(NotLoggedInException::new);
            if (!tokens.accessToken().equals(rejected.accessToken())) {
                return tokens;
            }
            return refreshAndSave(tokens);
        });
    }

    private GarminTokens refreshAndSave(GarminTokens tokens) {
        // Don't bother Garmin with a refresh token we know has run out.
        Instant now = clock.instant();
        if (tokens.refreshTokenExpiresAt() != null && !tokens.refreshTokenExpiresAt().isAfter(now)) {
            throw new SessionExpiredException();
        }
        GarminTokens refreshed = tokenClient.refresh(tokens);
        store.save(refreshed);
        return refreshed;
    }
}
