package com.hammez.stride.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A folder for personal data: the token file, capture's samples and the cache.
 * <ul>
 *   <li>Folder is {@code 700}, files are {@code 600}.</li>
 *   <li>Writes go to a temp file that is then moved into place, so a crash can't leave half a file.</li>
 *   <li>Never reads or writes through a symlink, and refuses the folder if it is itself a symlink. Parent folders
 *       are followed on purpose: on macOS {@code /tmp} and {@code /var} are symlinks.</li>
 * </ul>
 * This is the lowest layer, so it depends on nothing else in stride-mcp. It throws plain I/O errors and each
 * caller words them for the user:
 * <ul>
 *   <li>{@link NotDirectoryException}: the folder is a link or not a folder</li>
 *   <li>{@link java.nio.file.AtomicMoveNotSupportedException}: the folder can't do atomic moves</li>
 *   <li>{@link UnsupportedOperationException}: the folder has no Unix permissions (FAT, exFAT, a zip)</li>
 * </ul>
 */
public final class PrivateFolder {

    public static final Set<PosixFilePermission> FOLDER_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    public static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private final Path dir;

    public PrivateFolder(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** True if there is something at the folder's path, even a symlink. */
    public boolean exists() {
        return Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
    }

    /** @throws NotDirectoryException if the folder is a link or not a folder */
    public void requireRealFolder() throws NotDirectoryException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotDirectoryException(dir.toString());
        }
    }

    /** True if the folder is a real folder that is exactly {@code 700}. Changes nothing. */
    public boolean isPrivate() throws IOException {
        requireRealFolder();
        return view(dir).readAttributes().permissions().equals(FOLDER_PERMISSIONS);
    }

    /**
     * Creates the folder as {@code 700} if it's missing (and any missing parents, also {@code 700}).
     * An existing folder is tightened to {@code 700}.
     */
    public void create() throws IOException {
        if (exists()) {
            requireRealFolder();
        } else {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(FOLDER_PERMISSIONS));
        }
        // Set explicitly: the umask may have loosened the permissions given at creation.
        setPermissions(dir, FOLDER_PERMISSIONS);
    }

    /** The path of a file in the folder. The name must be one plain file name, so it can't point elsewhere. */
    public Path file(String name) {
        Path file = dir.resolve(name).normalize();
        if (name.isEmpty() || !dir.equals(file.getParent()) || !file.getFileName().toString().equals(name)) {
            throw new IllegalArgumentException("Not a plain file name: " + name);
        }
        return file;
    }

    /**
     * Writes a {@code 600} file atomically: to a temp file named {@code .<name><digits>.tmp}, then moved into place.
     * Replaces a symlink with that name rather than writing through it. The folder must exist: see {@link #create()}.
     */
    public void write(String name, byte[] content) throws IOException {
        Path file = file(name);
        Path temp = null;
        try {
            temp = Files.createTempFile(dir, "." + name, ".tmp", PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            setPermissions(temp, FILE_PERMISSIONS);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort. The temp file is 600 and holds nothing the real file wouldn't.
                }
            }
        }
    }

    /**
     * Reads a file in the folder, without following a symlink.
     *
     * @return empty if there's no file by that name
     * @throws FileSystemException if there's something by that name that isn't a regular file
     */
    public Optional<byte[]> read(String name) throws IOException {
        Path file = file(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileSystemException(file.toString(), null, "a link or not a regular file");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                InputStream in = Channels.newInputStream(channel)) {
            return Optional.of(in.readAllBytes());
        }
    }

    /** True if the folder has a regular file by that name (not a symlink). */
    public boolean contains(String name) {
        return Files.isRegularFile(file(name), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The names of everything in the folder.
     *
     * @throws NotDirectoryException if the folder is a link or not a folder
     */
    public List<String> list() throws IOException {
        requireRealFolder();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        }
    }

    /** Deletes a file in the folder (a symlink itself, not what it points to). Returns false if there was none. */
    public boolean delete(String name) throws IOException {
        return Files.deleteIfExists(file(name));
    }

    /** Deletes the folder itself if it is a real folder with nothing in it. Returns false if it wasn't deleted. */
    public boolean deleteIfEmpty() throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !list().isEmpty()) {
            return false;
        }
        Files.delete(dir);
        return true;
    }

    /** Sets a file's or folder's permissions, if they aren't already those. Never follows a symlink. */
    public static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        PosixFileAttributeView view = view(path);
        try {
            if (!view.readAttributes().permissions().equals(permissions)) {
                view.setPermissions(permissions);
            }
        } catch (FileSystemException e) {
            throw new IOException("Couldn't set permissions on " + path, e);
        }
    }

    private static PosixFileAttributeView view(Path path) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new UnsupportedOperationException("No POSIX permissions for " + path);
        }
        return view;
    }
}
