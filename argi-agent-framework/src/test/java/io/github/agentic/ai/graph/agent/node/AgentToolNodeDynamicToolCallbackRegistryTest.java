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
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.tool.TypedTool;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for model-turn dynamic tool binding snapshots on {@link AgentToolNode}.
 *
 * <p>
 * Covers the Human-in-the-Loop resumption scenario reported in issue #35: skill tools are
 * disclosed dynamically at model-call time and passed to the tool node through
 * {@code config.context()}, which is cleared when a subgraph resumes after an
 * interruption. The immutable snapshot keeps only this model turn's tools resolvable
 * across resumption.
 * </p>
 *
 * @author Kal'tsit
 * @since 1.0.0
 */
@DisplayName("AgentToolNode Dynamic Tool Registry Tests")
class AgentToolNodeDynamicToolCallbackRegistryTest {

	private Tool queryTool() {
		return TypedTool.builder("queryTool", (QueryInput input) -> "result:" + input.q())
			.description("Query tool")
			.inputType(QueryInput.class)
			.build();
	}

	record QueryInput(String q) {
	}

	private AgentToolNode buildNode(AgentToolBindingStore bindings) {
		return AgentToolNode.builder()
			.agentName("test-agent")
			.toolBindingStore(bindings)
			.toolExecutionTimeout(Duration.ofMinutes(5))
			.throwOnToolError(false)
			.build();
	}

	private OverAllState stateWithToolCall(String toolName, String arguments) {
		ChatMessage assistantMessage = ChatMessage.assistant("", List.of(new ToolCall("call_1", toolName, arguments)));
		Map<String, Object> stateData = new HashMap<>();
		stateData.put("messages", new ArrayList<>(List.of(assistantMessage)));
		return new OverAllState(stateData);
	}

	@Test
	@DisplayName("Dynamic registry resolves a tool when the config context is empty (HITL resume scenario)")
	void dynamicRegistryResolvesToolWhenConfigContextIsEmpty() throws Exception {
		AgentToolBindingStore bindings = new AgentToolBindingStore();
		AgentToolNode node = buildNode(bindings);
		OverAllState state = stateWithToolCall("queryTool", "{\"q\":\"hello\"}");
		var selected = new AgentToolBindings(new ToolRegistry(List.of(queryTool())));
		var configured = new AgentToolBindings(new ToolRegistry(List.of()));
		List<ChatMessage> messages = (List<ChatMessage>) state.data().get("messages");
		messages.set(0, bindings.attach(messages.get(0), selected, configured));

		// Simulate a resumed run: a fresh config whose context no longer carries the
		// dynamically disclosed tool callbacks.
		RunnableConfig config = RunnableConfig.builder().threadId("test-thread").build();

		Map<String, Object> result = node.apply(state, config);

		List<ChatMessage> responseMessages = (List<ChatMessage>) result.get("messages");
		assertThat(responseMessages).hasSize(1);
		assertThat(responseMessages.get(0).text()).contains("result:hello");
	}

	@Test
	@DisplayName("A tool absent from the registry is reported as unavailable")
	void toolAbsentFromRegistryIsReportedUnavailable() throws Exception {
		AgentToolNode node = buildNode(new AgentToolBindingStore());

		RunnableConfig config = RunnableConfig.builder().threadId("test-thread").build();

		Map<String, Object> result = node.apply(stateWithToolCall("unknownTool", "{}"), config);

		List<ChatMessage> responseMessages = (List<ChatMessage>) result.get("messages");
		assertThat(responseMessages).hasSize(1);
		assertThat(responseMessages.get(0).text()).contains("Tool not available: unknownTool");
	}

}
