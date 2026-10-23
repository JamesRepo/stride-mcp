package com.hammez.stride.garmin.auth;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.time.Instant;

/**
 * Login, whoami and logout. The commands use only this class, so tickets and tokens
 * never leave {@code garmin.auth}.
 */
public class GarminAuth {

    private final BrowserLogin browserLogin;
    private final DiTokenClient tokenClient;
    private final ProfileClient profileClient;
    private final TokenManager tokenManager;
    private final TokenStore store;

    public GarminAuth(BrowserLogin browserLogin, DiTokenClient tokenClient, ProfileClient profileClient,
            TokenManager tokenManager, TokenStore store) {
        this.browserLogin = browserLogin;
        this.tokenClient = tokenClient;
        this.profileClient = profileClient;
        this.tokenManager = tokenManager;
        this.store = store;
    }

    /** Signed-in user plus how long the saved session lasts. */
    public record WhoAmI(GarminProfile profile, Instant accessTokenExpiresAt, Instant sessionExpiresAt) {
    }

    /** Browser sign-in, then ticket swap, then profile check. Saves tokens only if all three work. */
    public WhoAmI login(BufferedReader in, PrintStream out) {
        String ticket = browserLogin.readTicket(in, out);
        out.println("Got the ticket. Swapping it for tokens...");
        GarminTokens tokens = tokenClient.exchangeTicket(ticket);
        GarminProfile profile = profileClient.fetch(tokens.accessToken());
        store.save(tokens);
        return new WhoAmI(profile, tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt());
    }

    /**
     * Fetches the profile with the saved tokens, refreshing them first if needed. If Garmin turns down
     * an access token that should still be good, refreshes once and tries once more.
     */
    public WhoAmI whoami() {
        GarminTokens tokens = tokenManager.validTokens();
        GarminProfile profile;
        try {
            profile = profileClient.fetch(tokens.accessToken());
        } catch (SessionExpiredException e) {
            tokens = tokenManager.refreshRejected(tokens);
            profile = profileClient.fetch(tokens.accessToken());
        }
        return new WhoAmI(profile, tokens.accessTokenExpiresAt(), tokens.refreshTokenExpiresAt());
    }

    /** Deletes the saved tokens. Returns false if there were none. */
    public boolean logout() {
        return store.delete();
    }

    public String tokenFile() {
        return store.file().toString();
    }
}
