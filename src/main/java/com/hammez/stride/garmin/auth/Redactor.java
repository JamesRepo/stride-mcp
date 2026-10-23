package com.hammez.stride.garmin.auth;

import java.util.regex.Pattern;

/** Removes tickets and tokens from text (such as Garmin error bodies) before it is shown. */
public final class Redactor {

    private static final int MAX_LENGTH = 200;
    private static final Pattern TOKEN_FIELD = Pattern.compile(
            "(\"(?:access_token|refresh_token|id_token|service_ticket)\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]*)?");

    private Redactor() {
    }

    /**
     * Redacts secrets, flattens whitespace and cuts to a short snippet.
     *
     * @param secrets values the caller knows are secret (such as the refresh token just sent),
     *                hidden even where the patterns wouldn't spot them
     */
    public static String redact(String text, String... secrets) {
        if (text == null) {
            return "";
        }
        String clean = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                clean = clean.replace(secret, "<redacted>");
            }
        }
        clean = TOKEN_FIELD.matcher(clean).replaceAll("$1<redacted>$2");
        clean = GarminAuthConfig.TICKET.matcher(clean).replaceAll("ST-<redacted>");
        clean = JWT.matcher(clean).replaceAll("<redacted>");
        clean = clean.replaceAll("\\s+", " ").strip();
        return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH) + "…" : clean;
    }
}
