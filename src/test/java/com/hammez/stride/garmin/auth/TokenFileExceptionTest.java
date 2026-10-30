package com.hammez.stride.garmin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TokenFileExceptionTest {

    @Test
    void explainedGivesTheReasonAndWhatToDo() {
        // Java's AccessDeniedException carries only the path, so its message alone doesn't say why.
        var e = new TokenFileException("Couldn't read the token file /home/jo/.stride-mcp/garmin_tokens.json",
                new AccessDeniedException("/home/jo/.stride-mcp/garmin_tokens.json"), Path.of("/home/jo/.stride-mcp"));

        assertThat(e.explained()).isEqualTo("Couldn't read the token file /home/jo/.stride-mcp/garmin_tokens.json "
                + "(access denied). Check that /home/jo/.stride-mcp and the files in it are yours and can be read "
                + "and written.");
        // The CLI's own wording is unchanged.
        assertThat(e.getMessage()).isEqualTo("Couldn't read the token file /home/jo/.stride-mcp/garmin_tokens.json");
    }

    @Test
    void theReasonFromJavasReasonItsMessageOrItsName() {
        assertThat(TokenFileException.reason(new FileSystemException("/a", null, "Is a directory")))
                .isEqualTo("is a directory");
        assertThat(TokenFileException.reason(new IOException("No space left on device")))
                .isEqualTo("no space left on device");
        assertThat(TokenFileException.reason(new NoSuchFileException("/a"))).isEqualTo("no such file");
        assertThat(TokenFileException.reason(new IOException())).isEqualTo("an I/O error");
    }
}
