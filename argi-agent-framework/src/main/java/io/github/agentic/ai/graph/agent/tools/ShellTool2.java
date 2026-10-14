/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.tools;

import io.github.agentic.ai.graph.RunnableConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.agent.tool.TypedTool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.agentic.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;

/**
 * A provider-neutral tool for executing shell commands.
 *
 * This tool allows for the execution of shell commands within a managed session. The
 * session's lifecycle and configuration are handled by the {@link ShellSessionManager}.
 *
 * Register {@link #tool()} with an agent's tool registry.
 *
 * Example of creating an instance: <pre>
 * ShellTool2 shellTool2 = ShellTool2.builder("/tmp/agent-workspace")
 *     .withName("shell")
 *     .build();
 * </pre>
 */
public class ShellTool2 {

	private static final Logger log = LoggerFactory.getLogger(ShellTool2.class);

	public static final String DEFAULT_TOOL_DESCRIPTION = "Execute a shell command inside a persistent session. Before running a command, "
			+ "confirm the working directory is correct (e.g., inspect with `ls` or `pwd`) and ensure "
			+ "any parent directories exist. Prefer absolute paths and quote paths containing spaces, "
			+ "such as `cd \"/path/with spaces\"`. Chain multiple commands with `&&` or `;` instead of "
			+ "embedding newlines. Avoid unnecessary `cd` usage unless explicitly required so the "
			+ "session remains stable. Outputs may be truncated when they become very large, and long "
			+ "running commands will be terminated once their configured timeout elapses.";

	private final ShellSessionManager sessionManager;

	/**
	 * Constructs a new ShellTool2.
	 * @param sessionManager The manager for the shell session. Must not be null.
	 */
	public ShellTool2(ShellSessionManager sessionManager) {
		if (sessionManager == null) {
			throw new IllegalArgumentException("ShellSessionManager cannot be null");
		}
		this.sessionManager = sessionManager;
	}

	// @formatter:off
	public String executeShellCommand(
		String command,
		Boolean restart,
		ToolContext toolContext) { // @formatter:on

		try {
			RunnableConfig config = (RunnableConfig) toolContext.attributes().get(AGENT_CONFIG_CONTEXT_KEY);

			// Handle restart request
			if (Boolean.TRUE.equals(restart)) {
				log.info("Restarting shell session as requested.");
				sessionManager.restartSession(config);
				if (command == null || command.trim().isEmpty()) {
					return "Shell session restarted successfully.";
				}
			}

			// Execute command
			if (command == null || command.trim().isEmpty()) {
				return "Error: Command cannot be empty.";
			}

			log.info("Executing shell command ({} characters)", command.length());
			ShellSessionManager.CommandResult result = sessionManager.executeCommand(command, config);

			// Format the output
			return formatResult(result);

		}
		catch (Exception e) {
			log.error("Shell command execution failed unexpectedly.", e);
			return "Error: " + e.getMessage();
		}
	}

	/** Build the executable declaration without provider-specific reflection. */
	public Tool tool() {
		return TypedTool
			.builder("shell",
					(Request request, ToolContext context) -> executeShellCommand(request.command(), request.restart(),
							context))
			.description(DEFAULT_TOOL_DESCRIPTION)
			.inputType(Request.class)
			.build();
	}

	public record Request(String command, Boolean restart) {
	}

	private String formatResult(ShellSessionManager.CommandResult result) {
		StringBuilder outputBuilder = new StringBuilder();

		// Handle timeout
		if (result.isTimedOut()) {
			outputBuilder.append("Error: Command timed out.");
			return outputBuilder.toString();
		}

		// Append standard output/error
		String output = result.getOutput();
		outputBuilder.append(output.isEmpty() ? "<no output>" : output);

		// Add truncation info
		if (result.isTruncatedByLines()) {
			outputBuilder.append(String.format("\n\n... Output truncated at %d lines (observed %d).",
					sessionManager.getMaxOutputLines(), result.getTotalLines()));
		}
		if (result.isTruncatedByBytes() && sessionManager.getMaxOutputBytes() != null) {
			outputBuilder.append(String.format("\n\n... Output truncated at %d bytes (observed %d).",
					sessionManager.getMaxOutputBytes(), result.getTotalBytes()));
		}

		// Add exit code for non-zero exit codes
		if (result.getExitCode() != null && result.getExitCode() != 0) {
			outputBuilder.append("\n\nExit code: ").append(result.getExitCode());
		}

		return outputBuilder.toString();
	}

	public ShellSessionManager getSessionManager() {
		return sessionManager;
	}

	public static Builder builder(String workspaceRoot) {
		return new Builder(workspaceRoot);
	}

	public static class Builder {

		private final String workspaceRoot;

		private List<String> startupCommands;

		private List<String> shutdownCommands;

		private long commandTimeout = 60000;

		private int maxOutputLines = 1000;

		private List<String> shellCommand;

		private Map<String, String> environment;

		public Builder(String workspaceRoot) {
			this.workspaceRoot = workspaceRoot;
		}

		public Builder withStartupCommands(List<String> startupCommands) {
			this.startupCommands = startupCommands;
			return this;
		}

		public Builder withShutdownCommands(List<String> shutdownCommands) {
			this.shutdownCommands = shutdownCommands;
			return this;
		}

		public Builder withCommandTimeout(long commandTimeout) {
			this.commandTimeout = commandTimeout;
			return this;
		}

		public Builder withMaxOutputLines(int maxOutputLines) {
			this.maxOutputLines = maxOutputLines;
			return this;
		}

		public Builder withShellCommand(List<String> shellCommand) {
			this.shellCommand = shellCommand;
			return this;
		}

		public Builder withEnvironment(Map<String, String> environment) {
			this.environment = environment;
			return this;
		}

		public ShellTool2 build() {
			ShellSessionManager.Builder sessionManagerBuilder = ShellSessionManager.builder()
				.workspaceRoot(Path.of(workspaceRoot))
				.commandTimeout(commandTimeout)
				.maxOutputLines(maxOutputLines);

			if (startupCommands != null) {
				sessionManagerBuilder.setStartupCommand(startupCommands);
			}
			if (shutdownCommands != null) {
				sessionManagerBuilder.setShutdownCommand(shutdownCommands);
			}
			if (shellCommand != null) {
				sessionManagerBuilder.shellCommand(shellCommand);
			}
			if (environment != null) {
				sessionManagerBuilder.environment(environment);
			}

			ShellSessionManager sessionManager = sessionManagerBuilder.build();
			return new ShellTool2(sessionManager);
		}

	}

}
