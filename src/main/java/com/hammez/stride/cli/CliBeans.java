package com.hammez.stride.cli;

import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hammez.stride.garmin.GarminSamples;
import com.hammez.stride.garmin.auth.GarminAuth;

@Configuration(proxyBeanMethods = false)
class CliBeans {

    @Bean
    public StrideCommands strideCommands(final GarminAuth auth, final GarminSamples samples) {
        return new StrideCommands(auth, samples, CliIo.system(), ZoneId.systemDefault());
    }
}
