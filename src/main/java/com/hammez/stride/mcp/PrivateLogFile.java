package com.hammez.stride.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

import com.hammez.stride.files.PrivateFolder;

/**
 * Serve mode: gets the log file ready before Spring's logging opens it.
 * <ul>
 *   <li>A missing folder is created as {@code 700}. Left to the logging library, a missing {@code ~/.stride-mcp}
 *       would be created open to everyone. An existing folder is left as it is: it may be one the owner named,
 *       such as their home folder.</li>
 *   <li>The file is {@code 600}. The logging setup ({@code logback-serve.xml}) only ever appends to it, so it
 *       stays that way.</li>
 *   <li>A file bigger than {@link #MAX_BYTES} is moved to {@code <name>.old} (replacing the last one), and a new
 *       one is started. So the log stays small without the logging library's rolling, which would create new
 *       files with the default permissions.</li>
 *   <li>A link or a folder at the log path is refused, so nothing is written through a link.</li>
 * </ul>
 * Runs after the settings are read (so {@code --stride.log-file=…} counts) and just before logging starts.
 */
public final class PrivateLogFile implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final long MAX_BYTES = 10 * 1024 * 1024;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String name = event.getEnvironment().getProperty("logging.file.name");
        if (name == null || name.isBlank()) {
            return;
        }
        Path file = Path.of(name).toAbsolutePath().normalize();
        try {
            prepare(file, MAX_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't prepare the log file " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER - 1;
    }

    static void prepare(Path file, long maxBytes) throws IOException {
        Path dir = file.getParent();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            new PrivateFolder(dir).create();
        } else if (!Files.isDirectory(dir)) {
            throw new NotDirectoryException(dir.toString());
        }

        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileSystemException(file.toString(), null, "not a plain file (a link or a folder)");
            }
            if (Files.size(file) <= maxBytes) {
                PrivateFolder.setPermissions(file, PrivateFolder.FILE_PERMISSIONS);
                return;
            }
            try {
                // A rename keeps the file's 600.
                Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (NoSuchFileException e) {
                // Another serve, started at the same moment, moved it first.
            }
        }
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PrivateFolder.FILE_PERMISSIONS));
        } catch (FileAlreadyExistsException e) {
            // Another serve, started at the same moment, created it first, also as 600.
        }
    }
}
