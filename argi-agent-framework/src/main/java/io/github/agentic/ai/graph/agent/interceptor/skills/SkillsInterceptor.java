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
package io.github.agentic.ai.graph.agent.interceptor.skills;

import io.github.agentic.ai.graph.agent.hook.skills.ReadSkillTool;
import io.github.agentic.ai.graph.agent.hook.skills.SkillsAgentHook;
import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.skills.SkillMetadata;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.internal.JsonCodec;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.agentic.ai.graph.skills.SkillPromptConstants.buildSkillsPrompt;

/**
 * Interceptor for integrating Claude-style Skills into ReactAgent.
 *
 * This interceptor injects skills metadata into system prompt, following progressive
 * disclosure pattern: - Injects lightweight skills list (name + description + path) -
 * Injects registry type and skill load instructions from the SkillRegistry - LLM reads
 * full SKILL.md content when needed using `read_skill` tool
 *
 * <p>
 * <b>Registration:</b>
 * <ul>
 * <li><b>Recommended:</b> Usually registered automatically via {@link SkillsAgentHook},
 * which creates and configures this interceptor along with the `read_skill` tool.</li>
 * <li><b>Manual:</b> Can also be manually created and registered if you need more control
 * over the interceptor configuration.</li>
 * </ul>
 *
 * Skills loading is handled by SkillsAgentHook in beforeAgent (if using SkillsAgentHook).
 * This interceptor reads from a shared SkillRegistry to inject skills into the system
 * prompt. The interceptor uses the SkillRegistry's generic methods (getRegistryType(),
 * getSkillLoadInstructions()) to build the prompt, making it compatible with any
 * SkillRegistry implementation.
 *
 * <p>
 * <b>Usage Examples:</b>
 *
 * <p>
 * <b>Automatic registration via SkillsAgentHook (recommended):</b> <pre>
 * FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder().build();
 * SkillsAgentHook hook = SkillsAgentHook.builder()
 *     .skillRegistry(registry)
 *     .autoReload(true)
 *     .build();
 * // SkillsInterceptor is automatically created and registered by the hook
 * </pre>
 *
 * <p>
 * <b>Manual registration with grouped tools (skill name → tools for dynamic
 * injection):</b> <pre>
 * Map&lt;String, ToolRegistry&gt; groupedTools = Map.of("my-skill", new ToolRegistry(List.of(myTool)));
 * SkillsInterceptor interceptor = SkillsInterceptor.builder()
 *     .skillRegistry(registry)
 *     .groupedTools(groupedTools)
 *     .build();
 * </pre>
 *
 * <p>
 * When {@link #groupedTools} is configured, this interceptor scans {@link ModelRequest}
 * messages for assistant {@link ChatMessage}s with tool calls named
 * {@value ReadSkillTool#READ_SKILL}. For each such call, the <i>skill_name</i> argument
 * is recorded. Tools from {@link #getGroupedTools()} for those skill names are then added
 * to the request's {@link ModelRequest#getDynamicToolRegistry() dynamicToolRegistry}.
 */
public class SkillsInterceptor extends ModelInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(SkillsInterceptor.class);

	private final SkillRegistry skillRegistry;

	private final Map<String, ToolRegistry> groupedTools;

	private final ToolRegistry toolRegistry;

	private SkillsInterceptor(Builder builder) {
		if (builder.skillRegistry == null) {
			throw new IllegalArgumentException("SkillRegistry must be provided. Use SkillsAgentHook to load skills.");
		}
		this.skillRegistry = builder.skillRegistry;
		this.groupedTools = builder.groupedTools != null ? builder.groupedTools : Collections.emptyMap();
		this.toolRegistry = builder.toolRegistry;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		List<SkillMetadata> skills = skillRegistry.listAll();

		if (skills.isEmpty()) {
			return handler.call(request);
		}

		// 1. Extract resolved skills from AssistantMessage with read_skill tool calls
		List<SkillMetadata> readSkills = extractReadSkills(request.getMessages());

		// 2. Collect tools from groupedTools and allowed_tools for those skills
		Map<String, Tool> skillTools = new LinkedHashMap<>();
		for (var definition : request.getDynamicToolRegistry().definitions()) {
			skillTools.put(definition.name(), request.getDynamicToolRegistry().find(definition.name()).orElseThrow());
		}
		Map<String, ToolRegistry> grouped = getGroupedTools();
		for (SkillMetadata skill : readSkills) {
			ToolRegistry toolsForSkill = grouped.get(skill.getName());
			if (toolsForSkill != null && !toolsForSkill.definitions().isEmpty()) {
				for (var definition : toolsForSkill.definitions()) {
					skillTools.putIfAbsent(definition.name(), toolsForSkill.find(definition.name()).orElseThrow());
				}
				if (logger.isInfoEnabled()) {
					logger.info("SkillsInterceptor: added {} tool(s) for skill '{}' to dynamicToolRegistry",
							toolsForSkill.definitions().size(), skill.getName());
				}
			}
			for (var tool : resolveAllowedTools(skill)) {
				skillTools.putIfAbsent(tool.definition().name(), tool);
			}
		}

		String skillsPrompt = buildSkillsPrompt(skills, skillRegistry, skillRegistry.getSystemPromptTemplate());
		ChatMessage enhanced = enhanceSystemMessage(request.getSystemMessage(), skillsPrompt);

		if (logger.isDebugEnabled()) {
			logger.debug("Enhanced system message:\n{}", enhanced.text());
		}

		ModelRequest modified = ModelRequest.builder(request)
			.systemMessage(enhanced)
			.dynamicToolRegistry(new ToolRegistry(skillTools.values()))
			.build();

		return handler.call(modified);
	}

	private List<SkillMetadata> extractReadSkills(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		Map<String, SkillMetadata> skillsByName = new LinkedHashMap<>();
		for (ChatMessage message : messages) {
			if (message.role() != ChatRole.ASSISTANT || message.toolCalls().isEmpty()) {
				continue;
			}
			for (ToolCall toolCall : message.toolCalls()) {
				if (!ReadSkillTool.READ_SKILL.equals(toolCall.name())) {
					continue;
				}
				resolveSkillFromArguments(toolCall.arguments())
					.ifPresent(skill -> skillsByName.putIfAbsent(skill.getName(), skill));
			}
		}
		return List.copyOf(skillsByName.values());
	}

	private Optional<SkillMetadata> resolveSkillFromArguments(String arguments) {
		Map<String, Object> parsedArguments = parseArguments(arguments);
		if (parsedArguments.isEmpty()) {
			return Optional.empty();
		}

		String skillName = getStringValue(parsedArguments, "skill_name");
		String skillPath = getStringValue(parsedArguments, "skill_path");
		if (skillName == null && skillPath == null) {
			return Optional.empty();
		}

		Optional<SkillMetadata> skillByName = skillName != null ? skillRegistry.get(skillName) : Optional.empty();
		Optional<SkillMetadata> skillByPath = skillPath != null ? skillRegistry.getByPath(skillPath) : Optional.empty();
		if (skillName != null && skillPath != null) {
			if (skillByName.isEmpty() || skillByPath.isEmpty()) {
				return Optional.empty();
			}
			if (!skillByName.get().getName().equals(skillByPath.get().getName())) {
				if (logger.isDebugEnabled()) {
					logger.debug("Ignoring read_skill call because skill_name '{}' and skill_path '{}' do not match",
							skillName, skillPath);
				}
				return Optional.empty();
			}
			return skillByName;
		}
		return skillByName.isPresent() ? skillByName : skillByPath;
	}

	private Map<String, Object> parseArguments(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return Map.of();
		}
		try {
			return new JsonCodec().fromJsonToMap(arguments);
		}
		catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to parse read_skill arguments: {}", e.getMessage());
			}
		}
		return Map.of();
	}

	private static String getStringValue(Map<String, Object> arguments, String key) {
		if (arguments == null || arguments.isEmpty()) {
			return null;
		}
		Object value = arguments.get(key);
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private List<Tool> resolveAllowedTools(SkillMetadata skill) {
		if (skill.getAllowedTools().isEmpty()) {
			return List.of();
		}
		List<Tool> resolvedTools = new ArrayList<>();
		for (String toolName : skill.getAllowedTools()) {
			Optional<Tool> tool = toolRegistry.find(toolName);
			if (tool.isEmpty()) {
				logger.debug("SkillsInterceptor: allowed tool '{}' declared by skill '{}' could not be resolved",
						toolName, skill.getName());
				continue;
			}
			resolvedTools.add(tool.orElseThrow());
		}
		return resolvedTools;
	}

	/**
	 * Returns an immutable snapshot while retaining the shared map for subsequent model
	 * turns.
	 */
	public Map<String, ToolRegistry> getGroupedTools() {
		return Map.copyOf(groupedTools);
	}

	private ChatMessage enhanceSystemMessage(ChatMessage existing, String skillsSection) {
		if (existing == null) {
			return ChatMessage.system(skillsSection);
		}
		return existing.appendText("\n\n" + skillsSection);
	}

	@Override
	public String getName() {
		return getClass().getSimpleName();
	}

	public static class Builder {

		private SkillRegistry skillRegistry;

		private Map<String, ToolRegistry> groupedTools;

		private ToolRegistry toolRegistry = new ToolRegistry(List.of());

		/**
		 * Set a shared SkillRegistry instance. This must be the same instance used by
		 * SkillsAgentHook to share skills data.
		 * @param skillRegistry the SkillRegistry to use (must not be null)
		 * @return this builder
		 */
		public Builder skillRegistry(SkillRegistry skillRegistry) {
			this.skillRegistry = skillRegistry;
			return this;
		}

		/**
		 * Set grouped tools: a shared map from skill name to its immutable tool registry.
		 * When the interceptor finds {@value ReadSkillTool#READ_SKILL} tool calls in
		 * AssistantMessage with a given skill_name, it adds the corresponding tools to
		 * the request's {@link ModelRequest#getDynamicToolRegistry()
		 * dynamicToolRegistry}.
		 * @param groupedTools map from skill name to registry (can be null or empty)
		 * @return this builder
		 */
		public Builder groupedTools(Map<String, ToolRegistry> groupedTools) {
			this.groupedTools = groupedTools;
			return this;
		}

		/** Set the available bindings for additive {@code allowed_tools} declarations. */
		public Builder toolRegistry(ToolRegistry toolRegistry) {
			this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
			return this;
		}

		public SkillsInterceptor build() {
			return new SkillsInterceptor(this);
		}

	}

}
