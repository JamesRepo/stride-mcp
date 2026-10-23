package com.hammez.stride.garmin.auth;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class GarminAuthBeans {

    @Bean
    GarminAuthConfig garminAuthConfig() {
        return GarminAuthConfig.defaults();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient garminHttpClient(GarminAuthConfig config) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.timeout())
                .build();
    }

    @Bean
    TokenStore tokenStore(
            @Value("${stride.token-file}") Path file,
            JsonMapper json) {
        return new TokenStore(file, json);
    }

    @Bean
    BrowserLogin browserLogin(GarminAuthConfig config) {
        return new BrowserLogin(config, BrowserOpener.system());
    }

    @Bean
    DiTokenClient diTokenClient(HttpClient garminHttpClient, GarminAuthConfig config, JsonMapper json, Clock clock) {
        return new DiTokenClient(garminHttpClient, config, json, clock);
    }

    @Bean
    TokenManager tokenManager(TokenStore store, DiTokenClient tokenClient, GarminAuthConfig config, Clock clock) {
        return new TokenManager(store, tokenClient, config, clock);
    }

    @Bean
    GarminApi garminApi(HttpClient garminHttpClient, GarminAuthConfig config, TokenManager tokenManager) {
        return new GarminApi(garminHttpClient, config, tokenManager);
    }

    @Bean
    ProfileClient profileClient(GarminApi garminApi, GarminAuthConfig config, JsonMapper json) {
        return new ProfileClient(garminApi, config, json);
    }

    @Bean
    GarminAuth garminAuth(BrowserLogin browserLogin, DiTokenClient tokenClient, ProfileClient profileClient,
            TokenStore store) {
        return new GarminAuth(browserLogin, tokenClient, profileClient, store);
    }
}
