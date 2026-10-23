package com.hammez.stride.garmin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void hidesTicketsTokensAndJwts() {
        String body = """
                {"access_token":"opaque-a","refresh_token":"opaque-r","ticket":"ST-123-abc.def-cas",
                 "note":"token eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjF9.c2ln here"}""";

        String redacted = Redactor.redact(body);

        assertThat(redacted)
                .doesNotContain("opaque-a", "opaque-r", "123-abc", "eyJhbGciOiJSUzI1NiJ9", "eyJleHAiOjF9")
                .contains("\"access_token\":\"<redacted>\"", "ST-<redacted>", "here");
    }

    @Test
    void keepsHarmlessErrorsReadable() {
        assertThat(Redactor.redact("{\"error\":\"invalid_client\"}")).isEqualTo("{\"error\":\"invalid_client\"}");
    }

    @Test
    void shortensLongBodies() {
        assertThat(Redactor.redact("x".repeat(1000))).hasSize(201).endsWith("…");
        assertThat(Redactor.redact(null)).isEmpty();
    }
    @Test
    void hidesSecretsTheCallerNames() {
        assertThat(Redactor.redact("bad token opaque-refresh-1 sent", "opaque-refresh-1", null, ""))
                .isEqualTo("bad token <redacted> sent");
    }
}
