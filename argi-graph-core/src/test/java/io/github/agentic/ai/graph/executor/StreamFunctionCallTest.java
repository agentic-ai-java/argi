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

package io.github.agentic.ai.graph.executor;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.stream.ScriptedChatClient;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.stream.LLmNodeAction;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #4406 reproduction test: complete neutral tool calls must survive graph streaming
 * and state aggregation.
 */
public class StreamFunctionCallTest {

	@Test
	void testCompleteToolCallSurvivesStreamAndStateAggregation() throws Exception {
		ChatClient chatModel = createMockChatModelWithSplitToolCallChunks();

		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, io.github.agentic.ai.graph.KeyStrategy> keyStrategyMap = new HashMap<>();
			keyStrategyMap.put("messages", new AppendStrategy());
			return keyStrategyMap;
		}).addNode("llmNode", node_async(new LLmNodeAction(chatModel, "llmNode")))
			.addEdge(START, "llmNode")
			.addEdge("llmNode", END);

		CompiledGraph compiledGraph = stateGraph.compile();

		Map<String, Object> input = new HashMap<>();
		input.put(OverAllState.DEFAULT_INPUT_KEY, "weather in beijing");

		Optional<OverAllState> finalState = compiledGraph.invoke(input);
		assertTrue(finalState.isPresent());

		List<?> messages = finalState.get().value("messages", List.class).orElseThrow();
		assertNotNull(messages);
		assertTrue(!messages.isEmpty());

		Object lastMessage = messages.get(messages.size() - 1);
		assertInstanceOf(ChatMessage.class, lastMessage);

		ChatMessage assistantMessage = (ChatMessage) lastMessage;
		assertTrue(assistantMessage.hasToolCalls());
		assertEquals(1, assistantMessage.toolCalls().size());

		ToolCall toolCall = assistantMessage.toolCalls().get(0);
		assertEquals("call_1", toolCall.id());
		assertEquals("get_weather", toolCall.name());
		assertEquals("{\"city\":\"Beijing\"}", toolCall.arguments());

		var outputs = compiledGraph.stream(input).collectList().block(java.time.Duration.ofSeconds(5));
		assertNotNull(outputs);
		assertTrue(outputs.stream()
			.filter(io.github.agentic.ai.graph.streaming.StreamingOutput.class::isInstance)
			.map(output -> (io.github.agentic.ai.graph.streaming.StreamingOutput<?>) output)
			.flatMap(output -> output.messages().stream())
			.flatMap(message -> message.toolCalls().stream())
			.allMatch(call -> call.id() != null && !call.id().isBlank() && call.name() != null
					&& !call.name().isBlank()));
	}

	private static ChatClient createMockChatModelWithSplitToolCallChunks() {
		ToolCall call = new ToolCall("call_1", "get_weather", "{\"city\":\"Beijing\"}");
		ChatResponse response = new ChatResponse(null, null, List.of(ChatMessage.assistant("", List.of(call))),
				List.of(call), FinishReason.TOOL_CALLS, null, Map.of());
		return new ScriptedChatClient(() -> Flux.just(ChatEvent.toolCall(call), ChatEvent.response(response)));
	}

}
