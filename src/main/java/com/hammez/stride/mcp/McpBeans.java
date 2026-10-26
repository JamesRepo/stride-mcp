package com.hammez.stride.mcp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.hammez.stride.garmin.GarminClient;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serve mode only. Spring AI's starter builds the MCP server around this transport instead of its own, which
 * would write to {@code System.out}. {@link McpStreams} is registered by {@code main}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("serve")
class McpBeans {

    @Bean
    StdioServer stdioServer(@Qualifier("mcpServerJsonMapper") JsonMapper json, McpStreams streams) {
        return new StdioServer(new JacksonMcpJsonMapper(json), streams);
    }

    /** Spring AI's annotation scanner finds the {@code @McpTool} methods on this bean. */
    @Bean
    GarminTools garminTools(GarminClient garminClient) {
        return new GarminTools(garminClient);
    }
}
