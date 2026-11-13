package dev.dguzman.jvminspector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the JVM Inspector MCP server.
 * <p>
 * The server talks MCP over STDIO: the AI client (Antigravity, Claude Desktop, ...) launches this
 * process and exchanges JSON-RPC messages through stdin/stdout. Because stdout is the protocol
 * channel, NOTHING else may be written to it (no banner, no console logging) - see application.properties.
 * <p>
 * Tools are discovered automatically: any Spring bean method annotated with {@code @McpTool} or
 * {@code @McpPrompt} is registered by Spring AI's annotation scanner.
 */
@SpringBootApplication
public class JvmInspectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(JvmInspectorApplication.class, args);
    }
}

