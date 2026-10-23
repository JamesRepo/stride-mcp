package com.hammez.stride.garmin.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Step 1 of login: the user signs in on Garmin's page in their own browser and pastes back the
 * address they land on. We only take the single-use ticket ({@code ST-…}) out of it.
 */
public class BrowserLogin {

    private final GarminAuthConfig config;
    private final BrowserOpener browser;

    public BrowserLogin(GarminAuthConfig config, BrowserOpener browser) {
        this.config = config;
        this.browser = browser;
    }

    /** Opens the sign-in page, then reads the pasted address and returns the ticket in it. */
    public String readTicket(BufferedReader in, PrintStream out) {
        String signInUrl = config.signInUrl();
        out.println("Opening Garmin's sign-in page in your browser:");
        out.println();
        out.println("  " + signInUrl);
        out.println();
        if (!browser.open(signInUrl)) {
            out.println("(Couldn't open a browser. Open the address above yourself.)");
            out.println();
        }
        out.println("Log in there, including any MFA. Your browser will then fail to load a page on");
        out.println(URI.create(config.serviceUrl()).getHost() + ". That's expected. If it warns that the connection");
        out.println("isn't secure, don't click Continue.");
        out.println();
        out.println("Copy the full address from the address bar and paste it here.");
        out.print("Address: ");
        out.flush();

        String pasted;
        try {
            pasted = in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read the pasted address", e);
        }
        return extractTicket(pasted);
    }

    /**
     * Finds the ticket in what the user pasted: either the full address with {@code ?ticket=ST-…},
     * or just the ticket.
     *
     * @throws NoTicketFoundException if there's no ticket in it
     */
    public static String extractTicket(String pasted) {
        if (pasted == null) {
            throw new NoTicketFoundException();
        }
        String text = unwrap(pasted.strip());
        if (GarminAuthConfig.TICKET.matcher(text).matches()) {
            return text;
        }
        return ticketParameter(text)
                .filter(ticket -> GarminAuthConfig.TICKET.matcher(ticket).matches())
                .orElseThrow(NoTicketFoundException::new);
    }

    /** Drops quotes or angle brackets around the paste. A closing one is only dropped if it matches. */
    private static String unwrap(String text) {
        if (text.isEmpty()) {
            return text;
        }
        int opener = "\"'<".indexOf(text.charAt(0));
        if (opener < 0) {
            return text;
        }
        text = text.substring(1);
        if (!text.isEmpty() && text.charAt(text.length() - 1) == "\"'>".charAt(opener)) {
            text = text.substring(0, text.length() - 1);
        }
        return text.strip();
    }

    private static Optional<String> ticketParameter(String text) {
        String query;
        try {
            query = new URI(text).getRawQuery();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals("ticket")) {
                try {
                    return Optional.of(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    // A broken %-escape: treat it like any other bad paste.
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
