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
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.graph.agent.extension.interceptor.AssistantMessageSanitizerInterceptor;
import io.github.agentic.ai.graph.agent.hook.Hook;
import io.github.agentic.ai.graph.agent.interceptor.Interceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.node.AgentLlmNode;
import io.github.agentic.ai.graph.agent.node.AgentToolNode;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import org.apache.commons.collections4.CollectionUtils;
import io.github.agentic.ai.graph.agent.tool.ToolSchema;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import io.github.agentic.ai.graph.agent.node.AgentToolBindingStore;

public class DefaultBuilder extends Builder {

	public static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING = "LLM may have adapted the tool name '{}', especially if the name was truncated due to length limits. If this is the case, you can customize the prefixing and processing logic using McpToolNamePrefixGenerator";

	@Override
	public ReactAgent build() {

		// Validate name is not empty
		if (!StringUtils.hasText(this.name)) {
			throw new IllegalArgumentException("Agent name must not be empty");
		}

		if (chatClient == null) {
			throw new IllegalArgumentException("ChatClient must be provided");
		}

		AgentLlmNode.Builder llmNodeBuilder = AgentLlmNode.builder()
			.agentName(this.name)
			.generationOptions(this.generationOptions)
			.throwOnModelError(this.throwOnModelError)
			.toolRegistry(this.toolRegistry)
			.chatClient(chatClient);

		if (outputKey != null && !outputKey.isEmpty()) {
			llmNodeBuilder.outputKey(outputKey);
		}

		if (systemPrompt != null) {
			llmNodeBuilder.systemPrompt(systemPrompt);
		}

		if (templateRenderer != null) {
			llmNodeBuilder.templateRenderer(templateRenderer);
		}

		if (instruction != null) {
			llmNodeBuilder.instruction(instruction);
		}

		String outputSchema = null;
		if (StringUtils.hasLength(this.outputSchema)) {
			outputSchema = this.outputSchema;
		}
		else if (this.outputType != null) {
			outputSchema = "Return only valid JSON matching this schema: " + ToolSchema.forType(this.outputType);
		}

		if (StringUtils.hasLength(outputSchema)) {
			llmNodeBuilder.outputSchema(outputSchema);
		}

		separateInterceptorsByType();

		registerDefaultModelInterceptors();

		List<Tool> allTools = gatherLocalTools();
		ToolRegistry bindings = new ToolRegistry(allTools);
		llmNodeBuilder.toolRegistry(bindings);

		if (enableLogging) {
			llmNodeBuilder.enableReasoningLog(true);
		}

		AgentToolBindingStore toolBindings = new AgentToolBindingStore();
		llmNodeBuilder.toolBindingStore(toolBindings);

		AgentLlmNode llmNode = llmNodeBuilder.build();

		// Setup tool node with all available tools
		AgentToolNode toolNode;
		AgentToolNode.Builder toolBuilder = AgentToolNode.builder()
			.agentName(this.name)
			.toolRegistry(bindings)
			.parallelToolExecution(this.parallelToolExecution)
			.maxParallelTools(this.maxParallelTools)
			.toolExecutionTimeout(this.toolExecutionTimeout)
			.validateToolArguments(this.validateToolArguments)
			.toolBindingStore(toolBindings)
			.businessOutcomeMappers(this.businessOutcomeMappers);

		toolBuilder.throwOnToolError(throwOnToolError);

		if (enableLogging) {
			toolBuilder.enableActingLog(true);
		}

		if (toolContext != null && !toolContext.isEmpty()) {
			toolBuilder.toolContext(toolContext);
		}

		toolNode = toolBuilder.build();

		return new ReactAgent(llmNode, toolNode, buildConfig(), this);
	}

	/**
	 * Register framework-provided model interceptors that are enabled by default.
	 * <p>
	 * The {@link AssistantMessageSanitizerInterceptor} is appended last so it sits
	 * innermost in the interceptor chain (closest to the model call). This way it
	 * normalizes the message list only after any user-configured interceptors have run,
	 * right before the request is sent to the model. Registration is skipped when
	 * disabled via {@link Builder#assistantMessageSanitizerEnabled(boolean)} or when an
	 * interceptor with the same name is already present.
	 */
	protected void registerDefaultModelInterceptors() {
		if (!this.assistantMessageSanitizerEnabled) {
			return;
		}
		if (modelInterceptors == null) {
			modelInterceptors = new ArrayList<>();
		}
		boolean alreadyPresent = modelInterceptors.stream()
			.anyMatch(interceptor -> AssistantMessageSanitizerInterceptor.class.equals(interceptor.getClass()));
		if (!alreadyPresent) {
			modelInterceptors.add(AssistantMessageSanitizerInterceptor.builder().build());
		}
	}

	/**
	 * Separate unified interceptors by type into modelInterceptors and toolInterceptors.
	 */
	protected void separateInterceptorsByType() {
		if (CollectionUtils.isNotEmpty(interceptors)) {
			modelInterceptors = new ArrayList<>();
			toolInterceptors = new ArrayList<>();

			for (Interceptor interceptor : interceptors) {
				if (interceptor instanceof ModelInterceptor) {
					modelInterceptors.add((ModelInterceptor) interceptor);
				}
				if (interceptor instanceof ToolInterceptor) {
					toolInterceptors.add((ToolInterceptor) interceptor);
				}
			}
		}
	}

	/**
	 * Collect neutral tool bindings in hook, interceptor, then application order. The
	 * first binding for a name wins, matching the runtime's existing precedence.
	 * @return immutable tool bindings
	 */
	protected List<Tool> gatherLocalTools() {
		var bindings = new LinkedHashMap<String, Tool>();
		for (Hook hook : hooks) {
			for (Tool tool : hook.getTools()) {
				bindings.putIfAbsent(tool.definition().name(), tool);
			}
		}
		for (ModelInterceptor interceptor : modelInterceptors) {
			for (Tool tool : interceptor.getTools()) {
				bindings.putIfAbsent(tool.definition().name(), tool);
			}
		}
		for (var definition : toolRegistry.definitions()) {
			bindings.putIfAbsent(definition.name(), toolRegistry.find(definition.name()).orElseThrow());
		}
		return List.copyOf(bindings.values());
	}

}
