package com.hammez.stride.garmin;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hammez.stride.garmin.auth.GarminApi;
import com.hammez.stride.garmin.auth.GarminAuthConfig;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class GarminBeans {

    @Bean
    GarminDataConfig garminDataConfig(GarminAuthConfig authConfig) {
        return GarminDataConfig.defaults(authConfig);
    }

    @Bean
    GarminClient garminClient(GarminApi garminApi, GarminDataConfig config, JsonMapper json, Clock clock) {
        return new GarminClient(garminApi, config, json, clock, ZoneId.systemDefault());
    }

    @Bean
    GarminSamples garminSamples(GarminApi garminApi, GarminDataConfig config, JsonMapper json, Clock clock) {
        return new GarminSamples(garminApi, config, json, clock, ZoneId.systemDefault());
    }
}
