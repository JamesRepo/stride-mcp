package com.hammez.stride.garmin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import com.hammez.stride.files.PrivateFolder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Garmin's replies, saved as files so the next command doesn't ask Garmin again. One file per reply, named after
 * what it holds ({@code sleep-2026-10-22.json}). Each file holds Garmin's raw reply, not our records, so changing
 * a record never needs a clear-out. It never sees tokens.
 * <p>
 * The folder is kept like the token file (see {@link PrivateFolder}). The cache only uses a folder it made itself,
 * marked with a {@value #MARKER} file, so pointing {@code --stride.cache-dir} at a folder you already had never
 * changes its permissions or deletes its files. The cache must never be the reason a command fails: a broken file is
 * ignored and replaced, and if the folder can't be used (someone else's, a symlink, no Unix permissions), nothing is
 * read or saved and {@link #problem()} says why.
 */
public class GarminCache {

    /** A cache name, without {@code .json}. No dots or slashes, so it can't point outside the folder. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_~-]*");
    /** Our files, and the temp files {@link PrivateFolder#write} leaves if it is stopped half way. */
    private static final Pattern OURS = Pattern.compile("\\.?" + NAME.pattern() + "\\.json(\\d+\\.tmp)?");
    /** Written when the cache creates its folder. Without it, the folder isn't ours. */
    static final String MARKER = ".stride-mcp-cache";

    private final PrivateFolder folder;
    private final JsonMapper json;
    private final Clock clock;
    private volatile String problem;

    public GarminCache(Path dir, JsonMapper json, Clock clock) {
        this.folder = new PrivateFolder(dir);
        this.json = json;
        this.clock = clock;
    }

    public Path dir() {
        return folder.dir();
    }

    /** Why the cache couldn't be used or saved to, the last time it failed. Empty if it has worked so far. */
    public Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    /**
     * The reply saved under this name, if it's still fresh. A copy past its lifetime, or one that is broken,
     * is deleted.
     */
    Optional<String> get(String name) {
        String file = fileName(name);
        if (!ready()) {
            return Optional.empty();
        }
        try {
            Optional<byte[]> content = folder.read(file);
            if (content.isEmpty()) {
                return Optional.empty();
            }
            JsonNode entry = json.readTree(content.get());
            Instant savedAt = Instant.parse(entry.path("savedAt").asString());
            Instant expiresAt = Instant.parse(entry.path("expiresAt").asString());
            JsonNode body = entry.path("body");
            Instant now = clock.instant();
            // A copy saved "in the future" means the clock was turned back: don't trust its lifetime.
            if (body.isString() && !savedAt.isAfter(now) && now.isBefore(expiresAt)) {
                return Optional.of(body.asString());
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable or not our format: treat it as missing, and replace it.
        }
        remove(name);
        return Optional.empty();
    }

    /** Saves a reply under this name, to be used for {@code lifetime} from now. */
    void put(String name, String body, Duration lifetime) {
        String file = fileName(name);
        if (!ready()) {
            return;
        }
        Instant now = clock.instant();
        ObjectNode entry = json.createObjectNode();
        entry.put("savedAt", now.toString());
        entry.put("expiresAt", now.plus(lifetime).toString());
        entry.put("body", body);
        try {
            folder.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry));
        } catch (IOException | RuntimeException e) {
            problem = "Couldn't save " + folder.file(file) + " to the cache: " + e.getMessage();
        }
    }

    /** Deletes the copy saved under this name, if there is one. For a copy that turned out to be broken. */
    void remove(String name) {
        String file = fileName(name);
        try {
            folder.delete(file);
        } catch (IOException | RuntimeException e) {
            // Best effort. The next put replaces it.
        }
    }

    /**
     * Deletes every cache file, then the folder if nothing else is in it. For {@code logout}: logging out should
     * leave no personal data behind. Only touches a folder the cache made (with its {@value #MARKER} file), and in it
     * only files named like the cache's own. Never follows a symlinked folder.
     *
     * @return false if there was no cache folder of ours
     * @throws UncheckedIOException if a file couldn't be deleted
     */
    public boolean clear() {
        try {
            if (!folder.exists() || !isOurs()) {
                return false;
            }
            for (String name : folder.list()) {
                if (OURS.matcher(name).matches()) {
                    folder.delete(name);
                }
            }
            folder.delete(MARKER);
            folder.deleteIfEmpty();
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't delete the cache in " + dir(), e);
        }
    }

    /** True if the folder is a real folder that the cache made. */
    private boolean isOurs() {
        try {
            folder.requireRealFolder();
            return folder.contains(MARKER);
        } catch (NotDirectoryException e) {
            return false;
        }
    }

    /**
     * Creates the folder, with its {@value #MARKER} file, if it's missing. An existing folder is only used if the
     * cache made it, and is then tightened to {@code 700}, as for the token folder. False, with the
     * {@link #problem()} set, if it can't be used.
     */
    private boolean ready() {
        try {
            if (!folder.exists()) {
                try {
                    folder.create();
                    folder.write(MARKER, "Made by stride-mcp for its cache. `stride-mcp logout` deletes it.\n"
                            .getBytes(StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    // Don't leave a folder without its marker: it would look like someone else's from then on.
                    undoCreate();
                    throw e;
                }
            } else if (isOurs()) {
                folder.create();
            } else {
                folder.requireRealFolder();
                problem = "Not using the cache: " + dir() + " already exists and wasn't made by stride-mcp. "
                        + "Pick a folder that doesn't exist yet with --stride.cache-dir, and stride-mcp will create it.";
                return false;
            }
            return true;
        } catch (NotDirectoryException e) {
            problem = "Not using the cache: " + dir() + " is a link or not a folder. "
                    + "Remove it, or pick another folder with --stride.cache-dir.";
        } catch (UnsupportedOperationException e) {
            problem = "Not using the cache: " + dir() + " doesn't support Unix file permissions. "
                    + "Pick a folder on your main disk with --stride.cache-dir.";
        } catch (IOException e) {
            problem = "Not using the cache: couldn't create or tighten " + dir() + " (" + e.getMessage() + ").";
        }
        return false;
    }

    private void undoCreate() {
        try {
            folder.deleteIfEmpty();
        } catch (IOException | RuntimeException ignored) {
            // Best effort. The problem says what went wrong.
        }
    }

    private static String fileName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a cache name: " + name);
        }
        return name + ".json";
    }
}
