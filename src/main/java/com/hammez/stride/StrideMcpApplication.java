package com.hammez.stride;

import java.io.FileDescriptor;
import java.io.FileOutputStream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.hammez.stride.cli.StrideCommands;
import com.hammez.stride.mcp.McpStreams;
import com.hammez.stride.mcp.PrivateLogFile;
import com.hammez.stride.mcp.StdioServer;

/**
 * Two modes, picked before Spring starts:
 * <ul>
 *   <li>{@code serve}: the MCP server, over stdin and stdout, until the AI app closes stdin. Runs with the
 *       {@code serve} profile, which turns the server on and sends logs to a file.</li>
 *   <li>Any other command: runs once and exits with its own exit code. The MCP server stays off, so it
 *       never reads stdin (where {@code login} reads the paste).</li>
 * </ul>
 */
@SpringBootApplication
public class StrideMcpApplication {

	public static void main(String[] args) {
		if (StrideCommands.isServe(args)) {
			System.exit(serve(args));
		}
		// The commands run once and exit with their own exit code.
		System.exit(SpringApplication.exit(SpringApplication.run(StrideMcpApplication.class, args)));
	}

	/**
	 * @return 0 when the AI app closed stdin, its normal way to end the session; 1 if the server couldn't start,
	 *         or stopped because it couldn't read what the AI app sent
	 */
	private static int serve(String[] args) {
		// stdout is the MCP channel. Keep it for the transport, and send anything else printed there to stderr,
		// which AI apps keep as the server's log.
		var mcpOut = new FileOutputStream(FileDescriptor.out);
		System.setOut(System.err);

		var app = new SpringApplication(StrideMcpApplication.class);
		app.setAdditionalProfiles("serve");
		app.addListeners(new PrivateLogFile());
		app.addInitializers(context -> context.getBeanFactory()
			.registerSingleton("mcpStreams", new McpStreams(System.in, mcpOut)));
		ConfigurableApplicationContext context;
		try {
			context = app.run(args);
		} catch (RuntimeException e) {
			// The AI app shows the user stderr, and the log file may not exist yet.
			System.err.println("stride-mcp couldn't start the MCP server: " + reason(e));
			return 1;
		}
		String logFile = context.getEnvironment().getProperty("logging.file.name");
		boolean stdinEnded = true;
		try {
			stdinEnded = context.getBean(StdioServer.class).awaitClose();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		int exitCode = SpringApplication.exit(context);
		if (!stdinEnded) {
			System.err.println("stride-mcp stopped: it couldn't read a message from the AI app. Details are in "
					+ logFile + ".");
			return 1;
		}
		return exitCode;
	}

	/** The exception's message, plus its root cause's when that says something more. */
	static String reason(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = messageOf(e);
		if (root == e || message.contains(messageOf(root))) {
			return message;
		}
		return message + " (cause: " + messageOf(root) + ")";
	}

	private static String messageOf(Throwable e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

}
