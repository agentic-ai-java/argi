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
package io.github.agentic.ai.graph.agent.hook.shelltool;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.AgentHook;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.agent.tools.ShellSessionManager;
import io.github.agentic.ai.graph.agent.tools.ShellTool2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook for managing ShellTool2 session. This hook initializes the shell session before
 * the agent starts and cleans it up after the agent finishes. Supports ShellTool2 (@Tool
 * annotation).
 */
@HookPositions({ HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT })
public class ShellToolAgentHook extends AgentHook {

	private static final Logger log = LoggerFactory.getLogger(ShellToolAgentHook.class);

	private ShellTool2 shellTool2;

	/**
	 * Private constructor with ShellTool2 for builder pattern.
	 * @param shellTool2 the ShellTool2 instance to use
	 */
	private ShellToolAgentHook(ShellTool2 shellTool2) {
		this.shellTool2 = shellTool2;
	}

	/**
	 * Create a new builder instance.
	 * @return a new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
		ShellSessionManager sessionManager = getSessionManager();
		if (sessionManager == null) {
			log.warn("ShellToolAgentHook: No ShellTool2 configured, skipping initialization");
			return CompletableFuture.completedFuture(new HashMap<>());
		}

		log.info("ShellToolAgentHook: Initializing shell session before agent execution");

		try {
			sessionManager.initialize(config);
			log.info("Shell session initialized successfully");
		}
		catch (Exception e) {
			log.error("Failed to initialize shell session", e);
			throw new RuntimeException("Failed to initialize shell session", e);
		}

		return CompletableFuture.completedFuture(new HashMap<>());
	}

	@Override
	public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
		ShellSessionManager sessionManager = getSessionManager();
		if (sessionManager == null) {
			log.warn("ShellToolAgentHook: No ShellTool2 configured, skipping cleanup");
			return CompletableFuture.completedFuture(new HashMap<>());
		}

		log.info("ShellToolAgentHook: Cleaning up shell session after agent execution");

		try {
			sessionManager.cleanup(config);
			log.info("Shell session cleaned up successfully");
		}
		catch (Exception e) {
			log.error("Failed to cleanup shell session", e);
			// Don't throw exception in cleanup to avoid masking original errors
		}

		return CompletableFuture.completedFuture(new HashMap<>());
	}

	@Override
	public String getName() {
		return "ShellToolAgentHook";
	}

	@Override
	public List<Tool> getTools() {
		if (shellTool2 == null) {
			log.info("No ShellTool2 instance configured, creating default instance");
			this.shellTool2 = ShellTool2.builder(System.getProperty("user.dir")).build();
		}
		return List.of(shellTool2.tool());
	}

	/**
	 * Get the ShellTool2 instance owned by this hook.
	 * @return the ShellTool2 instance, or null before default initialization
	 */
	protected ShellTool2 getShellTool2() {
		return shellTool2;
	}

	/**
	 * Get the ShellSessionManager from ShellTool2.
	 * @return the ShellSessionManager instance, or null before default initialization
	 */
	private ShellSessionManager getSessionManager() {
		if (shellTool2 != null) {
			return shellTool2.getSessionManager();
		}
		return null;
	}

	/**
	 * Builder class for constructing ShellToolAgentHook instances.
	 */
	public static class Builder {

		private ShellTool2 shellTool2;

		/**
		 * Set the ShellTool2 instance.
		 * @param shellTool2 the ShellTool2 to use
		 * @return this builder instance
		 */
		public Builder shellTool2(ShellTool2 shellTool2) {
			this.shellTool2 = shellTool2;
			return this;
		}

		/**
		 * Build the ShellToolAgentHook instance.
		 * @return a new ShellToolAgentHook instance
		 */
		public ShellToolAgentHook build() {
			return new ShellToolAgentHook(shellTool2);
		}

	}

}
