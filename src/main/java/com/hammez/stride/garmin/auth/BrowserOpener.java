package com.hammez.stride.garmin.auth;

import java.io.IOException;
import java.util.Locale;

/** Opens an address in the user's browser. */
@FunctionalInterface
public interface BrowserOpener {

    /** Returns false if no browser could be opened. The caller prints the address anyway. */
    boolean open(String url);

    /** macOS {@code open}. On other systems it does nothing and the user copies the printed address. */
    static BrowserOpener system() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        return url -> {
            if (!mac) {
                return false;
            }
            try {
                return new ProcessBuilder("open", url).start().waitFor() == 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }
}
