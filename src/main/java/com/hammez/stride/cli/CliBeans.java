package com.hammez.stride.cli;

import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hammez.stride.garmin.GarminCache;
import com.hammez.stride.garmin.GarminClient;
import com.hammez.stride.garmin.GarminSamples;
import com.hammez.stride.garmin.auth.GarminAuth;

@Configuration(proxyBeanMethods = false)
class CliBeans {

    @Bean
    public StrideCommands strideCommands(final GarminAuth auth, final GarminSamples samples, final GarminCache cache,
            final GarminClient client) {
        return new StrideCommands(auth, samples, cache, client, CliIo.system(), ZoneId.systemDefault());
    }
}
