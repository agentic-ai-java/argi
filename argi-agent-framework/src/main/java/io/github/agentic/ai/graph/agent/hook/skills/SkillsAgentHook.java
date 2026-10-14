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
package io.github.agentic.ai.graph.agent.hook.skills;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.AgentHook;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.skills.SkillsInterceptor;
import io.github.agentic.ai.graph.skills.SkillMetadata;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.api.tool.ToolRegistry;

import io.github.agentic.ai.api.tool.Tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentHook for integrating Skills with ReactAgent.
 *
 * This hook provides a complete Skills integration solution: - Manages skill loading and
 * reloading from the SkillRegistry - Provides the `read_skill` tool for LLM to read
 * SKILL.md files - Automatically creates and configures SkillsInterceptor to inject
 * skills into system prompts
 *
 * The hook wraps a SkillRegistry instance and shares it with SkillsInterceptor. The
 * SkillRegistry must be provided explicitly - it will not be created automatically.
 *
 * Skills can be optionally reloaded in beforeAgent if auto-reload is enabled. The hook
 * uses the SkillRegistry's generic interface methods, making it compatible with any
 * SkillRegistry implementation (FileSystemSkillRegistry, DatabaseSkillRegistry, etc.).
 *
 * <p>
 * <b>Example Usage:</b> <pre>{@code
 * // Create a FileSystemSkillRegistry first
 * FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder()
 *     .userSkillsDirectory("~/argi/skills")
 *     .projectSkillsDirectory("./skills")
 *     .build();
 *
 * // Then create the hook with the registry
 * SkillsAgentHook hook = SkillsAgentHook.builder()
 *     .skillRegistry(registry)
 *     .autoReload(true)
 *     .build();
 *
 * // With grouped tools (skill name → tools for dynamic injection when LLM calls read_skill)
 * Map<String, ToolRegistry> groupedTools = Map.of("my-skill", new ToolRegistry(List.of(myTool)));
 * SkillsAgentHook hookWithTools = SkillsAgentHook.builder()
 *     .skillRegistry(registry)
 *     .groupedTools(groupedTools)
 *     .build();
 * }</pre>
 */
@HookPositions(HookPosition.BEFORE_AGENT)
public class SkillsAgentHook extends AgentHook {

	private static final Logger logger = LoggerFactory.getLogger(SkillsAgentHook.class);

	private final SkillRegistry skillRegistry;

	private final boolean autoReload;

	private final Map<String, ToolRegistry> groupedTools;

	private final ToolRegistry toolRegistry;

	private final Tool readSkillTool;

	private final Tool searchSkillsTool;

	private final Tool disableSkillTool;

	private SkillsAgentHook(Builder builder) {
		if (builder.skillRegistry == null) {
			throw new IllegalArgumentException(
					"SkillRegistry must be provided. Use FileSystemSkillRegistry.builder() to create one.");
		}
		this.skillRegistry = builder.skillRegistry;
		this.autoReload = builder.autoReload;
		this.groupedTools = builder.groupedTools != null ? builder.groupedTools : Collections.emptyMap();
		this.toolRegistry = builder.toolRegistry;
		this.readSkillTool = ReadSkillTool.createReadSkillTool(this.skillRegistry, ReadSkillTool.DESCRIPTION);
		this.searchSkillsTool = SearchSkillsTool.createSearchSkillsTool(this.skillRegistry,
				SearchSkillsTool.DESCRIPTION);
		this.disableSkillTool = DisableSkillTool.createDisableSkillTool(this.skillRegistry,
				DisableSkillTool.DESCRIPTION);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
		// Reload skills if auto-reload is enabled
		if (autoReload) {
			try {
				skillRegistry.reload();
			}
			catch (UnsupportedOperationException e) {
				logger.debug("Reload not supported for registry type: {}", skillRegistry.getClass().getName());
			}
		}
		return CompletableFuture.completedFuture(Map.of());
	}

	/**
	 * Get the SkillRegistry instance used by this hook. This allows sharing the registry
	 * with SkillsInterceptor.
	 * @return the SkillRegistry instance
	 */
	public SkillRegistry getSkillRegistry() {
		return skillRegistry;
	}

	@Override
	public List<ModelInterceptor> getModelInterceptors() {
		SkillsInterceptor.Builder interceptorBuilder = SkillsInterceptor.builder().skillRegistry(this.skillRegistry);
		interceptorBuilder.groupedTools(this.groupedTools);
		interceptorBuilder.toolRegistry(this.toolRegistry);
		return List.of(interceptorBuilder.build());
	}

	@Override
	public List<io.github.agentic.ai.api.tool.Tool> getTools() {
		return List.of(readSkillTool, searchSkillsTool, disableSkillTool);
	}

	public int getSkillCount() {
		return skillRegistry.size();
	}

	public boolean hasSkill(String skillName) {
		return skillRegistry.contains(skillName);
	}

	public List<SkillMetadata> listSkills() {
		return skillRegistry.listAll();
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	/**
	 * Builder for creating SkillsAgentHook instances.
	 *
	 * <p>
	 * <b>Required:</b>
	 * <ul>
	 * <li><b>skillRegistry</b>: must be provided - use FileSystemSkillRegistry.builder()
	 * to create one</li>
	 * </ul>
	 *
	 * <p>
	 * <b>Optional:</b>
	 * <ul>
	 * <li><b>autoReload</b>: defaults to <code>false</code> - skills are loaded once
	 * during initialization</li>
	 * <li><b>groupedTools</b>: map from skill name to tool registry; passed to
	 * SkillsInterceptor so that when the LLM calls <code>read_skill</code> for a skill,
	 * those tools are added to the request's dynamicToolRegistry</li>
	 * </ul>
	 *
	 * <p>
	 * <b>Example Usage:</b> <pre>{@code
	 * // Create a FileSystemSkillRegistry first
	 * FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder()
	 *     .userSkillsDirectory("~/argi/skills")
	 *     .projectSkillsDirectory("./skills")
	 *     .build();
	 *
	 * // Then create the hook with the registry
	 * SkillsAgentHook hook = SkillsAgentHook.builder()
	 *     .skillRegistry(registry)
	 *     .autoReload(true)
	 *     .build();
	 *
	 * // Use a shared registry
	 * FileSystemSkillRegistry sharedRegistry = FileSystemSkillRegistry.builder().build();
	 * SkillsAgentHook hook1 = SkillsAgentHook.builder()
	 *     .skillRegistry(sharedRegistry)
	 *     .build();
	 * SkillsAgentHook hook2 = SkillsAgentHook.builder()
	 *     .skillRegistry(sharedRegistry)
	 *     .build();
	 * }</pre>
	 */
	public static class Builder {

		private SkillRegistry skillRegistry;

		private boolean autoReload = false;

		private Map<String, ToolRegistry> groupedTools;

		private ToolRegistry toolRegistry = new ToolRegistry(List.of());

		/**
		 * Sets the SkillRegistry instance.
		 * <p>
		 * <b>Required</b>: Must be provided. Use FileSystemSkillRegistry.builder() to
		 * create one. This is useful when you want to share a registry between multiple
		 * hooks or components.
		 * @param skillRegistry the SkillRegistry to use (must not be null)
		 * @return this builder
		 */
		public Builder skillRegistry(SkillRegistry skillRegistry) {
			this.skillRegistry = skillRegistry;
			return this;
		}

		/**
		 * Sets whether to automatically reload skills in beforeAgent.
		 * <p>
		 * <b>Optional</b>: Defaults to <code>false</code>
		 * <p>
		 * If true, skills will be reloaded on each agent invocation. If false, skills are
		 * loaded once during registry initialization.
		 * @param autoReload true to enable auto-reload, false to disable
		 * @return this builder
		 */
		public Builder autoReload(boolean autoReload) {
			this.autoReload = autoReload;
			return this;
		}

		/**
		 * Sets grouped tools: a shared map from skill name to its immutable tool
		 * registry.
		 * <p>
		 * <b>Optional</b>: When set, this map is passed to SkillsInterceptor. When the
		 * LLM calls <code>read_skill</code> with a given skill_name, the corresponding
		 * tools are added to the request's dynamicToolRegistry. Updates to this map are
		 * observed on later model turns; use a concurrent map when updating it while an
		 * agent is running.
		 * @param groupedTools map from skill name to registry (can be null or empty)
		 * @return this builder
		 */
		public Builder groupedTools(Map<String, ToolRegistry> groupedTools) {
			this.groupedTools = groupedTools;
			return this;
		}

		/**
		 * Sets the available bindings for additive allowed tools declared by skills.
		 * @param toolRegistry registry for tool names declared in skill metadata
		 * @return this builder
		 */
		public Builder toolRegistry(ToolRegistry toolRegistry) {
			this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
			return this;
		}

		/**
		 * Builds the SkillsAgentHook instance with the configured parameters.
		 * @return a new SkillsAgentHook instance
		 * @throws IllegalArgumentException if skillRegistry is not provided
		 */
		public SkillsAgentHook build() {
			return new SkillsAgentHook(this);
		}

	}

}
