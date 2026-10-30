package com.hammez.stride.garmin.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The token file, or its lock file, couldn't be read or written. The message names the file. Still an
 * {@link UncheckedIOException}, so the CLI shows it as before.
 */
public class TokenFileException extends UncheckedIOException {

    private final Path dir;

    TokenFileException(String message, IOException cause, Path dir) {
        super(message, cause);
        this.dir = dir;
    }

    /**
     * The message with the reason and what to do next, for a reader who can't see the cause: "Couldn't open the
     * lock file … (access denied). Check that … and the files in it are yours and can be read and written."
     */
    public String explained() {
        return getMessage() + " (" + reason(getCause()) + "). Check that " + dir
                + " and the files in it are yours and can be read and written.";
    }

    /** "is a directory", or for the exceptions that only carry the path (AccessDeniedException), "access denied". */
    static String reason(IOException e) {
        String reason = e instanceof FileSystemException fs ? fs.getReason() : e.getMessage();
        if (reason != null && !reason.isBlank()) {
            return reason.substring(0, 1).toLowerCase(Locale.ROOT) + reason.substring(1);
        }
        if (e.getClass() == IOException.class) {
            return "an I/O error";
        }
        return e.getClass().getSimpleName().replaceFirst("Exception$", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
