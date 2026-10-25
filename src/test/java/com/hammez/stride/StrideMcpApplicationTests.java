package com.hammez.stride;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import com.hammez.stride.cli.StrideCommands;

/** The command-line mode: the commands are there, and nothing of the MCP server is. */
@SpringBootTest
class StrideMcpApplicationTests {

	@Autowired
	ConfigurableApplicationContext context;

	@Test
	void commandsAreThere() {
		assertThat(context.getBeansOfType(StrideCommands.class)).hasSize(1);
	}

	@Test
	void noMcpServerOutsideServe() {
		// The server would read stdin, where login reads the paste.
		var factory = context.getBeanFactory();
		var mcpBeans = Arrays.stream(factory.getBeanDefinitionNames())
			.filter(name -> {
				Class<?> type = factory.getType(name);
				return type != null && (type.getName().startsWith("io.modelcontextprotocol.")
						|| type.getName().startsWith("org.springframework.ai.")
						|| type.getName().startsWith("com.hammez.stride.mcp."));
			})
			.toList();
		assertThat(mcpBeans).isEmpty();
	}

	@Test
	void startupErrorsSayTheCause() {
		var cause = new IOException("Permission denied");
		assertThat(StrideMcpApplication.reason(new IllegalStateException("Couldn't start", cause)))
			.isEqualTo("Couldn't start (cause: Permission denied)");
		assertThat(StrideMcpApplication.reason(new IllegalStateException("Couldn't start: Permission denied", cause)))
			.isEqualTo("Couldn't start: Permission denied");
		assertThat(StrideMcpApplication.reason(new IllegalStateException((String) null)))
			.isEqualTo("IllegalStateException");
		assertThat(StrideMcpApplication.reason(new IllegalStateException("Couldn't start", new NullPointerException())))
			.isEqualTo("Couldn't start (cause: NullPointerException)");
	}

}
