package com.hammez.stride.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** The cache files themselves. {@link GarminClientCacheTest} tests what the client does with them. */
class GarminCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @TempDir
    Path temp;

    private final JsonMapper json = JsonMapper.builder().build();
    private final TestClock clock = new TestClock(NOW);

    private Path dir() {
        return temp.resolve("cache");
    }

    private GarminCache cache() {
        return new GarminCache(dir(), json, clock);
    }

    @Test
    void savesTheRawReplyAndReadsItBack() throws IOException {
        cache().put("sleep-2026-10-22", "{\"dailySleepDTO\":{}}", Duration.ofMinutes(15));

        assertThat(cache().get("sleep-2026-10-22")).contains("{\"dailySleepDTO\":{}}");
        assertThat(json.readTree(Files.readString(dir().resolve("sleep-2026-10-22.json"))).path("body").asString())
                .isEqualTo("{\"dailySleepDTO\":{}}");
    }

    @Test
    void keepsAnEmptyReply() {
        cache().put("summary-2000-01-01", "", Duration.ofHours(1));

        assertThat(cache().get("summary-2000-01-01")).contains("");
    }

    @Test
    void aMissingEntryIsEmpty() {
        assertThat(cache().get("sleep-2026-10-22")).isEmpty();
    }

    @Test
    void anEntryLastsUntilItsLifetimeEndsAndIsThenDeleted() {
        cache().put("sleep-2026-10-22", "{}", Duration.ofMinutes(15));

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(cache().get("sleep-2026-10-22")).contains("{}");

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache().get("sleep-2026-10-22")).isEmpty();
        assertThat(dir().resolve("sleep-2026-10-22.json")).doesNotExist();
    }

    @Test
    void anEntrySavedInTheFutureIsNotTrusted() {
        // The clock was turned back after saving.
        clock.advance(Duration.ofDays(1));
        cache().put("sleep-2026-10-22", "{}", Duration.ofDays(30));
        clock.advance(Duration.ofDays(-1));

        assertThat(cache().get("sleep-2026-10-22")).isEmpty();
    }

    @Test
    void aBrokenFileIsIgnoredAndDeleted() throws IOException {
        for (String broken : List.of("{\"savedAt\":", "[]", "{}", "{\"savedAt\":\"yesterday\",\"expiresAt\":\"later\"}",
                "{\"savedAt\":\"2026-10-23T11:00:00Z\",\"expiresAt\":\"2026-10-24T11:00:00Z\",\"body\":{}}")) {
            cache().put("sleep-2026-10-22", "{}", Duration.ofDays(1));
            Files.writeString(dir().resolve("sleep-2026-10-22.json"), broken);

            assertThat(cache().get("sleep-2026-10-22")).as(broken).isEmpty();
            assertThat(dir().resolve("sleep-2026-10-22.json")).doesNotExist();
        }
    }

    @Test
    void createsAPrivateFolderAndPrivateFiles() throws IOException {
        cache().put("sleep-2026-10-22", "{}", Duration.ofDays(1));

        assertThat(permissions(dir())).isEqualTo("rwx------");
        assertThat(permissions(dir().resolve("sleep-2026-10-22.json"))).isEqualTo("rw-------");
        assertThat(permissions(dir().resolve(GarminCache.MARKER))).isEqualTo("rw-------");
        assertThat(filesIn(dir())).containsExactlyInAnyOrder(GarminCache.MARKER, "sleep-2026-10-22.json");
    }

    @Test
    void tightensItsOwnFolderAsForTheTokenFile() throws IOException {
        cache().put("sleep-2026-10-22", "{}", Duration.ofDays(1));
        Files.setPosixFilePermissions(dir(), PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(cache().get("sleep-2026-10-22")).contains("{}");

        assertThat(permissions(dir())).isEqualTo("rwx------");
    }

    @Test
    void aFolderStrideMcpDidntMakeIsNeverUsedChangedOrCleared() throws IOException {
        Files.createDirectory(dir(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.writeString(dir().resolve("package.json"), "{}");
        Files.writeString(dir().resolve("garmin_tokens.json"), "{}");
        GarminCache cache = cache();

        cache.put("sleep-2026-10-22", "{}", Duration.ofDays(1));

        assertThat(cache.get("sleep-2026-10-22")).isEmpty();
        assertThat(cache.clear()).isFalse();
        assertThat(filesIn(dir())).containsExactlyInAnyOrder("package.json", "garmin_tokens.json");
        assertThat(permissions(dir())).isEqualTo("rwxr-xr-x");
        assertThat(cache.problem()).hasValueSatisfying(problem -> assertThat(problem)
                .contains("Not using the cache", "wasn't made by stride-mcp", "--stride.cache-dir"));
    }

    @Test
    void replacesAFileByMovingANewOneIntoPlace() throws IOException {
        cache().put("sleep-2026-10-22", "{\"old\":1}", Duration.ofDays(1));
        cache().put("sleep-2026-10-22", "{\"new\":2}", Duration.ofDays(1));

        assertThat(cache().get("sleep-2026-10-22")).contains("{\"new\":2}");
        assertThat(filesIn(dir())).containsExactlyInAnyOrder(GarminCache.MARKER, "sleep-2026-10-22.json");
    }

    @Test
    void neverReadsOrWritesThroughASymlinkedFile() throws IOException {
        cache().put("summary-2026-10-21", "{}", Duration.ofDays(1));
        Path target = Files.writeString(temp.resolve("elsewhere.json"), Files.readString(
                dir().resolve("summary-2026-10-21.json")));
        Files.createSymbolicLink(dir().resolve("sleep-2026-10-22.json"), target);

        assertThat(cache().get("sleep-2026-10-22")).isEmpty();
        assertThat(dir().resolve("sleep-2026-10-22.json")).doesNotExist();

        Files.createSymbolicLink(dir().resolve("sleep-2026-10-22.json"), target);
        cache().put("sleep-2026-10-22", "{\"new\":2}", Duration.ofDays(1));
        assertThat(Files.isSymbolicLink(dir().resolve("sleep-2026-10-22.json"))).isFalse();
        assertThat(Files.readString(target)).doesNotContain("new");
    }

    @Test
    void aSymlinkedFolderIsNeverUsedAndSaysWhy() throws IOException {
        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"));
        Files.createSymbolicLink(dir(), elsewhere);
        GarminCache cache = cache();

        cache.put("sleep-2026-10-22", "{}", Duration.ofDays(1));

        assertThat(cache.get("sleep-2026-10-22")).isEmpty();
        assertThat(filesIn(elsewhere)).isEmpty();
        assertThat(cache.problem()).hasValueSatisfying(problem -> assertThat(problem)
                .contains("Not using the cache", dir().toString(), "is a link", "--stride.cache-dir"));
    }

    @Test
    void aFolderWithoutUnixPermissionsIsNeverUsedAndSaysWhy() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(temp.resolve("cache.zip"), Map.of("create", "true"))) {
            var cache = new GarminCache(zip.getPath("/cache"), json, clock);

            cache.put("sleep-2026-10-22", "{}", Duration.ofDays(1));

            assertThat(cache.get("sleep-2026-10-22")).isEmpty();
            assertThat(cache.problem()).hasValueSatisfying(problem -> assertThat(problem)
                    .contains("doesn't support Unix file permissions"));
        }
    }

    @Test
    void refusesANameThatCouldPointElsewhere() {
        for (String name : List.of("../tokens", "a/b", ".hidden", "", "Sleep")) {
            assertThatThrownBy(() -> cache().get(name)).as(name).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void clearDeletesTheCacheFilesAndTheFolder() throws IOException {
        cache().put("sleep-2026-10-22", "{}", Duration.ofDays(1));
        cache().put("activities-2026-10-17-to-2026-10-23-page-1", "[]", Duration.ofDays(1));
        Files.writeString(dir().resolve(".sleep-2026-10-21.json8812.tmp"), "{}");

        assertThat(cache().clear()).isTrue();

        assertThat(dir()).doesNotExist();
        assertThat(cache().clear()).isFalse();
    }

    @Test
    void clearOnlyDeletesFilesNamedLikeTheCachesOwn() throws IOException {
        cache().put("sleep-2026-10-22", "{}", Duration.ofDays(1));
        Files.writeString(dir().resolve("notes.txt"), "mine");
        Files.writeString(dir().resolve("Report.json"), "mine");

        assertThat(cache().clear()).isTrue();

        assertThat(filesIn(dir())).containsExactlyInAnyOrder("notes.txt", "Report.json");
    }

    @Test
    void clearLeavesASymlinkedFolderAndWhatItPointsToAlone() throws IOException {
        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("sleep-2026-10-22.json"), "{}");
        Files.writeString(elsewhere.resolve(GarminCache.MARKER), "");
        Files.createSymbolicLink(dir(), elsewhere);

        assertThat(cache().clear()).isFalse();

        assertThat(filesIn(elsewhere)).containsExactlyInAnyOrder("sleep-2026-10-22.json", GarminCache.MARKER);
        assertThat(Files.isSymbolicLink(dir())).isTrue();
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
