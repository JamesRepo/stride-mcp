package com.hammez.stride.mcp;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * The streams the MCP messages travel on: stdin, and the real stdout that {@code main} kept before pointing
 * {@code System.out} at stderr.
 */
public record McpStreams(InputStream in, OutputStream out) {
}
