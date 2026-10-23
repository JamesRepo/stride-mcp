package com.hammez.stride.garmin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BrowserLoginTest {

    private static final String TICKET = "ST-1234567-AbCdEfGh.ij_kl-cas";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET,
            "  https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET + "  \n",
            "https://mobile.integration.garmin.com/gcm/ios?foo=bar&ticket=" + TICKET + "&x=1",
            "\"https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET + "\"",
            "<https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET + ">",
            TICKET,
            "  " + TICKET + " ",
            "'" + TICKET + "'",
            "\"" + TICKET,
            "<https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET,
    })
    void findsTheTicketInAPastedAddressOrOnItsOwn(String pasted) {
        assertThat(BrowserLogin.extractTicket(pasted)).isEqualTo(TICKET);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "https://mobile.integration.garmin.com/gcm/ios",
            "https://mobile.integration.garmin.com/gcm/ios?ticket=",
            "https://mobile.integration.garmin.com/gcm/ios?ticket=not-a-ticket",
            "https://sso.garmin.com/sso/login?service=https://mobile.integration.garmin.com/gcm/ios",
            "ST-",
            "ST-abc def",
            "hello ST-abc",
            "https://example.com/?notticket=" + TICKET,
            "https://mobile.integration.garmin.com/gcm/ios?ticket=ST-abc%",
            "https://mobile.integration.garmin.com/gcm/ios?ticket=ST-abc%ZZ",
    })
    void rejectsPastesWithNoTicket(String pasted) {
        assertThatThrownBy(() -> BrowserLogin.extractTicket(pasted))
                .isInstanceOf(NoTicketFoundException.class)
                .hasMessageContaining("Copy the full address");
    }

    @Test
    void opensAndPrintsTheSignInPageThenReadsThePaste() {
        GarminAuthConfig config = GarminAuthConfig.defaults();
        List<String> opened = new ArrayList<>();
        var login = new BrowserLogin(config, url -> opened.add(url));
        var out = new ByteArrayOutputStream();

        String ticket = login.readTicket(
                new BufferedReader(new StringReader("https://mobile.integration.garmin.com/gcm/ios?ticket=" + TICKET + "\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(ticket).isEqualTo(TICKET);
        assertThat(opened).containsExactly(
                "https://sso.garmin.com/sso/login?service=https%3A%2F%2Fmobile.integration.garmin.com%2Fgcm%2Fios");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains(config.signInUrl()).doesNotContain(TICKET);
    }

    @Test
    void carriesOnWhenNoBrowserOpens() {
        var out = new ByteArrayOutputStream();
        var login = new BrowserLogin(GarminAuthConfig.defaults(), url -> false);

        String ticket = login.readTicket(new BufferedReader(new StringReader(TICKET + "\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(ticket).isEqualTo(TICKET);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Couldn't open a browser")
                .contains(GarminAuthConfig.defaults().signInUrl());
    }

    @Test
    void endOfInputMeansNoTicket() {
        var login = new BrowserLogin(GarminAuthConfig.defaults(), url -> true);

        assertThatThrownBy(() -> login.readTicket(new BufferedReader(new StringReader("")),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)))
                .isInstanceOf(NoTicketFoundException.class);
    }
    @Test
    void namesTheHostFromTheConfig() {
        GarminAuthConfig d = GarminAuthConfig.defaults();
        var config = new GarminAuthConfig(d.ssoBaseUrl(), "https://app.example.test/callback", d.diTokenUrl(),
                d.connectApiBaseUrl(), d.serviceTicketGrantType(), d.clientIds(), d.nativeHeaders(),
                d.refreshMargin(), d.timeout());
        var out = new ByteArrayOutputStream();

        new BrowserLogin(config, url -> true).readTicket(new BufferedReader(new StringReader(TICKET + "\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("app.example.test").doesNotContain("mobile.integration");
    }
}
