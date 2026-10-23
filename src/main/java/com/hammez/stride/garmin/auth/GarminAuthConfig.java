package com.hammez.stride.garmin.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.Pattern;

/**
 * Everything about Garmin's login that Garmin may change: addresses, app client IDs and headers.
 * Keep these values here and nowhere else. Based on python-garminconnect (checked October 2026).
 *
 * @param ssoBaseUrl          Garmin's sign-in site, opened in the browser
 * @param serviceUrl          the phone-app address the browser is sent back to, with {@code ?ticket=ST-…}
 * @param diTokenUrl          where tickets and refresh tokens are swapped for new tokens
 * @param connectApiBaseUrl   Garmin Connect's API, for the profile check and all data requests
 * @param serviceTicketGrantType the OAuth grant type for a ticket swap
 * @param clientIds           app client IDs to try, in order, for a ticket swap
 * @param nativeHeaders       headers the Garmin Android app sends on every API call
 * @param refreshMargin       refresh the access token when it has less than this left
 * @param timeout             connect and request timeout for every call to Garmin
 */
public record GarminAuthConfig(
        String ssoBaseUrl,
        String serviceUrl,
        String diTokenUrl,
        String connectApiBaseUrl,
        String serviceTicketGrantType,
        List<String> clientIds,
        SequencedMap<String, String> nativeHeaders,
        Duration refreshMargin,
        Duration timeout) {

    /** What a Garmin login ticket looks like. The login reads tickets with it and {@link Redactor} hides them. */
    static final Pattern TICKET = Pattern.compile("ST-[A-Za-z0-9._-]+");

    public GarminAuthConfig {
        clientIds = List.copyOf(clientIds);
        nativeHeaders = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(nativeHeaders));
    }

    public static GarminAuthConfig defaults() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", "GCM-Android-5.23");
        headers.put("X-Garmin-User-Agent", "com.garmin.android.apps.connectmobile/5.23; ; "
                + "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0");
        headers.put("X-Garmin-Paired-App-Version", "10861");
        headers.put("X-Garmin-Client-Platform", "Android");
        headers.put("X-App-Ver", "10861");
        headers.put("X-Lang", "en");
        headers.put("X-GCExperience", "GC5");
        headers.put("Accept-Language", "en-US,en;q=0.9");

        return new GarminAuthConfig(
                "https://sso.garmin.com",
                "https://mobile.integration.garmin.com/gcm/ios",
                "https://diauth.garmin.com/di-oauth2-service/oauth/token",
                "https://connectapi.garmin.com",
                "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket",
                List.of(
                        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
                        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
                        "GARMIN_CONNECT_MOBILE_ANDROID_DI",
                        "GARMIN_CONNECT_MOBILE_IOS_DI"),
                headers,
                Duration.ofMinutes(15),
                Duration.ofSeconds(30));
    }

    /** A copy pointing at other servers, for tests. */
    public GarminAuthConfig withBaseUrls(String ssoBaseUrl, String diTokenUrl, String connectApiBaseUrl) {
        return new GarminAuthConfig(ssoBaseUrl, serviceUrl, diTokenUrl, connectApiBaseUrl,
                serviceTicketGrantType, clientIds, nativeHeaders, refreshMargin, timeout);
    }

    /** Garmin's classic sign-in form. It redirects to {@link #serviceUrl} with a ticket. */
    public String signInUrl() {
        return ssoBaseUrl + "/sso/login?service=" + URLEncoder.encode(serviceUrl, StandardCharsets.UTF_8);
    }

    /** The profile check's path on {@link #connectApiBaseUrl}. */
    public String profilePath() {
        return "/userprofile-service/socialProfile";
    }

    /** Native headers plus the given extras (extras win). */
    Map<String, String> headersWith(Map<String, String> extra) {
        var headers = new LinkedHashMap<>(nativeHeaders);
        headers.putAll(extra);
        return headers;
    }
}
