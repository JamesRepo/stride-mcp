package com.hammez.stride.garmin.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.hammez.stride.files.PrivateFolder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the token file safely.
 * <ul>
 *   <li>Folder is {@code 700}, file is {@code 600}.</li>
 *   <li>Writes go to a temp file that is then moved into place, so a crash can't leave half a file.</li>
 *   <li>Never follows symlinks for the folder or the file.</li>
 *   <li>{@link #underLock} lets one stride-mcp process at a time load, refresh and save.</li>
 * </ul>
 * The file handling is shared with the cache, in {@link PrivateFolder}. This is the only class that knows how
 * tokens are stored.
 */
public class TokenStore {

    private final Path file;
    private final Path dir;
    private final PrivateFolder folder;
    private final Path lockFile;
    private final JsonMapper json;

    public TokenStore(Path file, JsonMapper json) {
        this.file = file.toAbsolutePath().normalize();
        this.dir = this.file.getParent();
        this.folder = new PrivateFolder(dir);
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.json = json;
    }

    public Path file() {
        return file;
    }

    /** The saved tokens, or empty if there is no token file. */
    public Optional<GarminTokens> load() {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            requireRealDirectory();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new GarminException("Token file " + file + " is a link or not a regular file. "
                        + "Run `stride-mcp logout`, then `stride-mcp login`.");
            }
            PrivateFolder.setPermissions(dir, PrivateFolder.FOLDER_PERMISSIONS);
            PrivateFolder.setPermissions(file, PrivateFolder.FILE_PERMISSIONS);
            return Optional.of(fromJson(Files.readAllBytes(file)));
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read the token file " + file, e);
        }
    }

    public void save(GarminTokens tokens) {
        byte[] content = toJson(tokens);
        try {
            createDirectory();
            folder.write(file.getFileName().toString(), content);
        } catch (AtomicMoveNotSupportedException e) {
            throw new GarminException("Can't save tokens safely: " + dir + " doesn't support atomic moves.", e);
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save the token file " + file, e);
        }
    }

    /**
     * Runs the action while holding a lock on the token file, shared with other stride-mcp processes.
     * Refresh tokens are single use, so without it two processes refreshing at once would leave one
     * holding a refresh token the other has already used up. Don't call this from inside the action.
     */
    public synchronized <T> T underLock(Supplier<T> action) {
        FileChannel channel;
        try {
            createDirectory();
            channel = FileChannel.open(lockFile,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PrivateFolder.FILE_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't open the lock file " + lockFile, e);
        }
        try (channel; FileLock lock = lockOrThrow(channel)) {
            return action.get();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't release the lock file " + lockFile, e);
        }
    }

    private FileLock lockOrThrow(FileChannel channel) {
        try {
            // Waits while another stride-mcp process refreshes.
            return channel.lock();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't lock the token file " + lockFile, e);
        }
    }

    /** Deletes the token file. Returns false if there was none. */
    public boolean delete() {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't delete the token file " + file, e);
        }
    }

    private void createDirectory() throws IOException {
        try {
            folder.create();
        } catch (NotDirectoryException e) {
            throw linkedFolder();
        }
    }

    private void requireRealDirectory() {
        try {
            folder.requireRealFolder();
        } catch (NotDirectoryException e) {
            throw linkedFolder();
        }
    }

    private GarminException linkedFolder() {
        return new GarminException("Token folder " + dir + " is a link or not a folder. "
                + "Remove it, then run `stride-mcp login`.");
    }

    private GarminException noUnixPermissions(UnsupportedOperationException e) {
        return new GarminException("Can't keep tokens private: " + dir + " doesn't support Unix file permissions. "
                + "Use a folder on your main disk (see --stride.token-file).", e);
    }

    private byte[] toJson(GarminTokens tokens) {
        ObjectNode node = json.createObjectNode();
        node.put("accessToken", tokens.accessToken());
        node.put("refreshToken", tokens.refreshToken());
        node.put("clientId", tokens.clientId());
        node.put("accessTokenExpiresAt", tokens.accessTokenExpiresAt().toString());
        if (tokens.refreshTokenExpiresAt() != null) {
            node.put("refreshTokenExpiresAt", tokens.refreshTokenExpiresAt().toString());
        }
        return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    private GarminTokens fromJson(byte[] content) {
        try {
            JsonNode node = json.readTree(content);
            String refreshExpiry = node.path("refreshTokenExpiresAt").asString(null);
            return new GarminTokens(
                    required(node, "accessToken"),
                    required(node, "refreshToken"),
                    required(node, "clientId"),
                    Instant.parse(required(node, "accessTokenExpiresAt")),
                    refreshExpiry == null ? null : Instant.parse(refreshExpiry));
        } catch (JacksonException | DateTimeParseException | IllegalStateException e) {
            // Don't attach the cause: its message could quote the file's contents.
            throw new GarminException("Token file " + file + " is unreadable. "
                    + "Run `stride-mcp logout`, then `stride-mcp login`.");
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException("missing " + field);
        }
        return value.asString();
    }
}
