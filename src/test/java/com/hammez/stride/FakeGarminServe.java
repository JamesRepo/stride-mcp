package com.hammez.stride;

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.hammez.stride.garmin.auth.FakeGarmin;
import com.hammez.stride.garmin.auth.GarminAuthConfig;

/**
 * Test only: points a {@code serve} process at a {@link FakeGarmin} in the test's JVM, and stops its clock. Loaded
 * only when a test passes {@code --spring.main.sources=com.hammez.stride.FakeGarminServe} with
 * {@code --stride.test.fake-garmin=<the fake's address>} and {@code --stride.test.now=<an instant>}. Not a
 * {@code @Configuration}: that is a {@code @Component}, which the app's component scan would find in every test.
 * Spring still reads the {@code @Bean} methods of a class it is given as a source.
 * <p>
 * Bean overriding is off in Spring Boot, so these don't replace the real beans: they are extra ones, marked
 * {@code @Primary}. Everything that uses them asks by type, so these win.
 */
class FakeGarminServe {

    static final String PRINTED = "a println during a tool call";

    /** Every address is the fake's. Anything but this computer is refused, so tokens can't go elsewhere. */
    @Bean
    @Primary
    GarminAuthConfig fakeGarminAuthConfig(@Value("${stride.test.fake-garmin}") String address) {
        URI fake = URI.create(address);
        if (!isLoopback(fake.getHost())) {
            throw new IllegalStateException("The fake Garmin must be on this computer, not " + fake.getHost());
        }
        String base = fake.toString();
        return GarminAuthConfig.defaults().withBaseUrls(base, base + FakeGarmin.TOKEN_PATH, base);
    }

    /**
     * Stopped at the test's moment, so "today" is the day the tool tests pin. It also prints to stdout each time
     * it's read, as a stray {@code println} in a tool would. The tools read it on every call.
     */
    @Bean
    @Primary
    Clock fakeGarminClock(@Value("${stride.test.now}") String now) {
        return new PrintingClock(Instant.parse(now), ZoneOffset.UTC);
    }

    private static boolean isLoopback(String host) {
        try {
            return host != null && InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class PrintingClock extends Clock {

        private final Instant now;
        private final ZoneId zone;

        PrintingClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        @Override
        public Instant instant() {
            System.out.println(PRINTED);
            return now;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new PrintingClock(now, other);
        }
    }
}
