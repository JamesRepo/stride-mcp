package com.hammez.stride.garmin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class TokenStoreTest {

    private static final GarminTokens TOKENS = new GarminTokens("access-1", "refresh-1", "CLIENT",
            Instant.parse("2026-10-10T09:00:00Z"), Instant.parse("2026-11-08T12:00:00Z"));

    @TempDir
    Path temp;

    private final JsonMapper json = JsonMapper.builder().build();

    private Path dir() {
        return temp.resolve(".stride-mcp");
    }

    private Path file() {
        return dir().resolve("garmin_tokens.json");
    }

    private TokenStore store() {
        return new TokenStore(file(), json);
    }

    @Test
    void loadsNothingWhenThereIsNoFile() {
        assertThat(store().load()).isEmpty();
    }

    @Test
    void savesAndLoads() {
        store().save(TOKENS);

        assertThat(store().load()).contains(TOKENS);
    }

    @Test
    void keepsAMissingRefreshExpiry() {
        var tokens = new GarminTokens("a", "r", "C", Instant.parse("2026-10-10T09:00:00Z"), null);
        store().save(tokens);

        assertThat(store().load()).contains(tokens);
    }

    @Test
    void createsAPrivateFolderAndFile() throws IOException {
        store().save(TOKENS);

        assertThat(permissions(dir())).isEqualTo("rwx------");
        assertThat(permissions(file())).isEqualTo("rw-------");
    }

    @Test
    void tightensAnExistingFolder() throws IOException {
        Files.createDirectory(dir(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));

        store().save(TOKENS);

        assertThat(permissions(dir())).isEqualTo("rwx------");
    }

    @Test
    void tightensLoosePermissionsOnLoad() throws IOException {
        store().save(TOKENS);
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(store().load()).contains(TOKENS);
        assertThat(permissions(file())).isEqualTo("rw-------");
        assertThat(permissions(dir())).isEqualTo("rwx------");
    }

    @Test
    void replacesTheFileByMovingANewOneIntoPlace() throws IOException {
        store().save(TOKENS);
        Object before = Files.readAttributes(file(), BasicFileAttributes.class).fileKey();
        var updated = new GarminTokens("access-2", "refresh-2", "CLIENT", TOKENS.accessTokenExpiresAt(), null);

        store().save(updated);

        Object after = Files.readAttributes(file(), BasicFileAttributes.class).fileKey();
        assertThat(after).isNotNull().isNotEqualTo(before);
        assertThat(store().load()).contains(updated);
        assertThat(permissions(file())).isEqualTo("rw-------");
        assertThat(filesIn(dir())).containsExactly("garmin_tokens.json");
    }

    @Test
    void leavesNoTempFileWhenTheMoveFails() throws IOException {
        // A non-empty folder where the file should go makes the final move fail.
        Files.createDirectories(file().resolve("blocker"));

        assertThatThrownBy(() -> store().save(TOKENS)).isInstanceOf(RuntimeException.class);

        assertThat(filesIn(dir())).containsExactly("garmin_tokens.json");
        assertThat(Files.isDirectory(file())).isTrue();
    }

    @Test
    void refusesASymlinkedFolder() throws IOException {
        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"));
        Files.createSymbolicLink(dir(), elsewhere);

        assertThatThrownBy(() -> store().save(TOKENS))
                .isInstanceOf(GarminException.class).hasMessageContaining("is a link");
        assertThat(filesIn(elsewhere)).isEmpty();
    }

    @Test
    void refusesToLoadASymlinkedFile() throws IOException {
        Files.createDirectory(dir());
        Path target = Files.writeString(temp.resolve("target.json"), "{}");
        Files.createSymbolicLink(file(), target);

        assertThatThrownBy(() -> store().load())
                .isInstanceOf(GarminException.class).hasMessageContaining("is a link");
    }

    @Test
    void saveReplacesASymlinkInsteadOfWritingThroughIt() throws IOException {
        Files.createDirectory(dir());
        Path target = Files.writeString(temp.resolve("target.json"), "untouched");
        Files.createSymbolicLink(file(), target);

        store().save(TOKENS);

        assertThat(Files.readString(target)).isEqualTo("untouched");
        assertThat(Files.isSymbolicLink(file())).isFalse();
        assertThat(store().load()).contains(TOKENS);
    }

    @Test
    void reportsACorruptFileWithoutQuotingIt() throws IOException {
        Files.createDirectory(dir());
        Files.writeString(file(), "{\"accessToken\":\"secret-access\",");

        assertThatThrownBy(() -> store().load())
                .isInstanceOf(GarminException.class)
                .hasMessageContaining("unreadable")
                .hasNoCause()
                .message().doesNotContain("secret-access");
    }

    @Test
    void reportsAFileWithMissingFields() throws IOException {
        Files.createDirectory(dir());
        Files.writeString(file(), "{\"accessToken\":\"a\"}");

        assertThatThrownBy(() -> store().load()).isInstanceOf(GarminException.class).hasMessageContaining("unreadable");
    }

    @Test
    void deletes() {
        store().save(TOKENS);

        assertThat(store().delete()).isTrue();
        assertThat(store().load()).isEmpty();
        assertThat(store().delete()).isFalse();
    }

    @Test
    void toStringHidesTokens() {
        assertThat(TOKENS.toString()).doesNotContain("access-1").doesNotContain("refresh-1").contains("CLIENT");
    }

    @Test
    void reportsAFileSystemWithoutUnixPermissions() throws IOException {
        // A zip file system has no Unix permissions, like FAT or exFAT.
        try (FileSystem zip = FileSystems.newFileSystem(temp.resolve("tokens.zip"), Map.of("create", "true"))) {
            Path file = zip.getPath("/garmin_tokens.json");
            Files.writeString(file, "{}");
            TokenStore store = new TokenStore(file, json);

            assertThatThrownBy(store::load).isExactlyInstanceOf(GarminException.class)
                    .hasMessageContaining("doesn't support Unix file permissions");
            assertThatThrownBy(() -> store.save(TOKENS)).isExactlyInstanceOf(GarminException.class)
                    .hasMessageContaining("doesn't support Unix file permissions");
        }
    }

    @Test
    void underLockKeepsTheLockFilePrivate() throws IOException {
        assertThat(store().underLock(() -> "done")).isEqualTo("done");

        assertThat(permissions(dir().resolve("garmin_tokens.json.lock"))).isEqualTo("rw-------");
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static java.util.List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
