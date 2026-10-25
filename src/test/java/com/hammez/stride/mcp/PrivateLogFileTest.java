package com.hammez.stride.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateLogFileTest {

    private static final long MAX = 100;

    @TempDir
    Path temp;

    @Test
    void createsAMissingFolderAs700AndTheFileAs600() throws IOException {
        Path file = temp.resolve("a/b/stride-mcp.log");

        PrivateLogFile.prepare(file, MAX);

        assertThat(file).isEmptyFile();
        assertThat(permissions(file)).isEqualTo("rw-------");
        assertThat(permissions(file.getParent())).isEqualTo("rwx------");
        assertThat(permissions(temp.resolve("a"))).isEqualTo("rwx------");
    }

    @Test
    void leavesAnExistingFolderAsItIs() throws IOException {
        // It may be a folder the owner named, like their home folder.
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwxr-xr-x"));

        PrivateLogFile.prepare(temp.resolve("stride-mcp.log"), MAX);

        assertThat(permissions(temp)).isEqualTo("rwxr-xr-x");
        assertThat(permissions(temp.resolve("stride-mcp.log"))).isEqualTo("rw-------");
    }

    @Test
    void tightensAnExistingFileAndKeepsItsLines() throws IOException {
        Path file = Files.writeString(temp.resolve("stride-mcp.log"), "earlier\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        PrivateLogFile.prepare(file, MAX);

        assertThat(file).hasContent("earlier");
        assertThat(permissions(file)).isEqualTo("rw-------");
    }

    @Test
    void startsANewFileWhenTheOldOneIsBig() throws IOException {
        Path file = Files.writeString(temp.resolve("stride-mcp.log"), "x".repeat((int) MAX + 1));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(temp.resolve("stride-mcp.log.old"), "the one before");

        PrivateLogFile.prepare(file, MAX);

        assertThat(file).isEmptyFile();
        assertThat(permissions(file)).isEqualTo("rw-------");
        Path old = temp.resolve("stride-mcp.log.old");
        assertThat(old).hasContent("x".repeat((int) MAX + 1));
        assertThat(permissions(old)).isEqualTo("rw-------");
    }

    @Test
    void refusesALinkAndLeavesItsTargetAlone() throws IOException {
        Path target = Files.writeString(temp.resolve("elsewhere.log"), "");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        Path file = Files.createSymbolicLink(temp.resolve("stride-mcp.log"), target);

        assertThatThrownBy(() -> PrivateLogFile.prepare(file, MAX))
                .isInstanceOf(FileSystemException.class)
                .hasMessageContaining("not a plain file");
        assertThat(permissions(target)).isEqualTo("rw-r--r--");
    }

    @Test
    void refusesAFolderAtTheLogPathAndLeavesItAlone() throws IOException {
        Path file = Files.createDirectory(temp.resolve("logs"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> PrivateLogFile.prepare(file, MAX))
                .isInstanceOf(FileSystemException.class)
                .hasMessageContaining("not a plain file");
        assertThat(permissions(file)).isEqualTo("rwxr-xr-x");
    }

    @Test
    void refusesAFileWhereTheFolderShouldBe() throws IOException {
        Path notAFolder = Files.writeString(temp.resolve("not-a-folder"), "");

        assertThatThrownBy(() -> PrivateLogFile.prepare(notAFolder.resolve("stride-mcp.log"), MAX))
                .isInstanceOf(NotDirectoryException.class);
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
