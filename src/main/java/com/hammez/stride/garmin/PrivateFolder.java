package com.hammez.stride.garmin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import com.hammez.stride.garmin.auth.GarminException;

/**
 * A folder for personal data, kept the same way as the token file.
 * <ul>
 *   <li>Folder is {@code 700}, files are {@code 600}.</li>
 *   <li>Writes go to a temp file that is then moved into place, so a crash can't leave half a file.</li>
 *   <li>Refuses the folder if it is itself a symlink. Parent folders are followed, as for the token file:
 *       on macOS {@code /tmp} and {@code /var} are symlinks.</li>
 *   <li>The folder is only created by the first {@link #write}, so a run that fails early leaves nothing.</li>
 * </ul>
 */
final class PrivateFolder {

    private static final Set<PosixFilePermission> DIR_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private final Path dir;

    PrivateFolder(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    Path dir() {
        return dir;
    }

    /**
     * Checks the folder can be used, without creating anything. A missing folder is fine. An existing one must be
     * a real folder that is already {@code 700}: its permissions are never loosened or changed for the user.
     */
    void check() {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new GarminException("Folder " + dir + " is a link or not a folder. Pick a new folder.");
        }
        try {
            if (!view(dir).readAttributes().permissions().equals(DIR_PERMISSIONS)) {
                throw new GarminException("Folder " + dir + " already exists and isn't private (700). "
                        + "Pick a new folder and stride-mcp will create it.");
            }
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read the permissions of " + dir, e);
        }
    }

    /** Creates the folder as {@code 700} if it's missing, otherwise {@link #check()}s it again. */
    private void createIfMissing() {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            check();
            return;
        }
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMISSIONS));
            // Set explicitly: the umask may have changed the permissions given at creation.
            view(dir).setPermissions(DIR_PERMISSIONS);
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't create the folder " + dir, e);
        }
    }

    boolean contains(String name) {
        return Files.exists(dir.resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    /** Writes a {@code 600} file atomically, creating the folder first if needed. */
    void write(String name, byte[] content) {
        createIfMissing();
        Path file = dir.resolve(name);
        Path temp = null;
        try {
            temp = Files.createTempFile(dir, "." + name, ".tmp", PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            view(temp).setPermissions(FILE_PERMISSIONS);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(ByteBuffer.wrap(content));
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (AtomicMoveNotSupportedException e) {
            throw new GarminException("Can't save files safely: " + dir + " doesn't support atomic moves.", e);
        } catch (UnsupportedOperationException e) {
            throw noUnixPermissions(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save " + file, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort. The temp file is 600.
                }
            }
        }
    }

    private GarminException noUnixPermissions(UnsupportedOperationException e) {
        return new GarminException("Can't keep files private: " + dir + " doesn't support Unix file permissions. "
                + "Use a folder on your main disk.", e);
    }

    private static PosixFileAttributeView view(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new UnsupportedOperationException("No POSIX permissions for " + path);
        }
        return view;
    }
}
