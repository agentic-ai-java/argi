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

package io.github.agentic.ai.agentic;

import io.github.agentic.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.ai.graph.GraphRepresentation;
import io.github.agentic.ai.graph.agent.BaseAgent;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import io.github.agentic.ai.adapter.springai.SpringAiToolAdapter;
import io.github.agentic.ai.api.tool.Tool;
import org.springframework.ai.tool.ToolCallbackProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static Agent Loader for programmatically provided agents.
 *
 * <p>
 * This loader takes a static list of pre-created agent instances and makes them available
 * through the AgentLoader interface. Perfect for cases where you already have agent
 * instances and just need a convenient way to wrap them in an AgentLoader.
 *
 * <p>
 * Works with or without MCP: when {@link ToolCallbackProvider} is absent (e.g. graph-only
 * profile), only {@code single_agent} (with PoetTool) is registered; when present, both
 * {@code single_agent} and {@code research_agent} are available.
 */
@Component
public class AgentStaticLoader implements AgentLoader {

	private final Map<String, BaseAgent> agents = new ConcurrentHashMap<>();

	public AgentStaticLoader(@Autowired(required = false) ToolCallbackProvider toolCallbackProvider) {
		ChatModel chatModel = new FixedResponseChatModel("test response");

		ReactAgent agent = ReactAgent.builder()
			.name("single_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver())
			.toolRegistry(SpringAiToolAdapter.fromCallbacks(PoetTool.createPoetToolCallback()))
			.build();

		this.agents.put("single_agent", agent);

		List<Tool> tools = toolCallbackProvider != null
				? java.util.Arrays.stream(toolCallbackProvider.getToolCallbacks())
					.<Tool>map(SpringAiToolAdapter::new)
					.toList()
				: Collections.emptyList();

		if (!tools.isEmpty()) {
			ReactAgent researchAgent = new DeepResearchAgent().getResearchAgent(tools);
			GraphRepresentation representation = researchAgent.getAndCompileGraph().stateGraph
				.getGraph(GraphRepresentation.Type.PLANTUML);
			this.agents.put("research_agent", researchAgent);
		}
	}

	@Override
	@NonNull public List<String> listAgents() {
		return agents.keySet().stream().toList();
	}

	@Override
	public BaseAgent loadAgent(String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Agent name cannot be null or empty");
		}

		BaseAgent agent = agents.get(name);
		if (agent == null) {
			throw new NoSuchElementException("Agent not found: " + name);
		}

		return agent;
	}

	private static final class FixedResponseChatModel implements ChatModel {

		private final String responseText;

		private FixedResponseChatModel(String responseText) {
			this.responseText = responseText;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
		}

	}

}
