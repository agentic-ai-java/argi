/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.ChatMetadataKeys;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.state.ReplaceAllWith;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamingOutputNeutralTest {

	private final ChatMessage first = ChatMessage.tool("call-1", "lookup", "first result");

	private final ChatMessage second = ChatMessage.tool("call-2", "lookup", "second result");

	@Test
	void copiesMessageListsAndExcludesToolTurnsFromVisibleText() {
		var source = new ArrayList<>(List.of(first, second,
				ChatMessage.assistant("not visible", List.of(new ToolCall("call-3", "lookup", "{}"))),
				ChatMessage.assistant("visible")));
		var output = new StreamingOutput<>(source, null, "node", "agent", new OverAllState(),
				OutputType.AGENT_TOOL_FINISHED);
		source.clear();
		assertEquals(4, output.messages().size());
		assertEquals("visible", output.text());
		assertThrows(UnsupportedOperationException.class, () -> output.messages().clear());
	}

	@Test
	void projectsNeutralEventsAndUsageWithoutAProviderMessage() {
		var usage = new Usage(12L, 3L, 15L);
		var response = new ChatResponse("id", "model", List.of(first, second), List.of(), FinishReason.STOP, usage,
				Map.of());
		var completed = new StreamingOutput<>(ChatEvent.response(response), "node", new OverAllState());
		assertEquals(List.of(first, second), completed.messages());
		assertEquals(usage, completed.tokenUsage());
		assertEquals("", completed.text());
		var delta = new StreamingOutput<>(new ChatEvent(ChatEvent.Type.TEXT_DELTA, "hello", null, null, null,
				Map.of(ChatMetadataKeys.REASONING, "thinking")), "node", new OverAllState());
		assertEquals("hello", delta.text());
		assertEquals("thinking", delta.messages().get(0).metadata().get(ChatMetadataKeys.REASONING));
		var usageOnly = new StreamingOutput<>(ChatEvent.usage(usage), "node", new OverAllState());
		assertEquals(List.of(), usageOnly.messages());
		assertEquals(usage, usageOnly.tokenUsage());
		for (var type : List.of(ChatEvent.Type.TEXT_DELTA, ChatEvent.Type.RESPONSE)) {
			var empty = new StreamingOutput<>(new ChatEvent(type, null, null, null, null, Map.of()), "node",
					new OverAllState());
			assertEquals(List.of(), empty.messages());
		}
	}

	@Test
	void graphPreservesAllDeltaMessagesAndSkipsStateMarkers() throws Exception {
		var context = context();
		var output = (StreamingOutput<?>) context.buildNodeOutput("tools",
				Map.of("messages", List.of(first, new Object(), second)), false);
		assertEquals(List.of(first, second), output.messages());
	}

	@Test
	void graphReplacementPublishesOnlyTheLatestToolTurn() throws Exception {
		var context = context();
		var history = new ReplaceAllWith<>(
				List.of(ChatMessage.user("question"), ChatMessage.assistant("calling tools"), first, second));
		var output = (StreamingOutput<?>) context.buildNodeOutput("tools", Map.of("messages", history), false);
		assertEquals(List.of(first, second), output.messages());
		var answer = ChatMessage.assistant("answer");
		var updated = new ReplaceAllWith<>(List.of(first, second, answer));
		var finalOutput = (StreamingOutput<?>) context.buildNodeOutput("model", Map.of("messages", updated), false);
		assertEquals(List.of(answer), finalOutput.messages());
	}

	@Test
	void independentToolResultsRetainCardinalityAndIdentity() throws Exception {
		var results = List.of(first, second);
		var context = context();
		var output = (StreamingOutput<?>) context.buildNodeOutput("tools", Map.of("messages", results), false);
		assertEquals(2, output.messages().size());
		for (int index = 0; index < output.messages().size(); index++) {
			var expected = List.of(first, second).get(index);
			var actual = output.messages().get(index);
			assertEquals(expected.content(), actual.content());
			assertEquals(expected.role(), actual.role());
			assertEquals(expected.toolCallId(), actual.toolCallId());
			assertEquals(expected.toolName(), actual.toolName());
			assertEquals(expected.metadata(), actual.metadata());
		}
	}

	@Test
	void graphTerminalEventHasNoMessageButRetainsUsage() throws Exception {
		var usage = new Usage(12L, 3L, 15L);
		var output = context().buildStreamingOutput(null, usage, "tools", false);
		assertEquals(List.of(), output.messages());
		assertEquals("", output.text());
		assertEquals(usage, output.tokenUsage());
	}

	private static GraphRunnerContext context() throws Exception {
		var graph = new StateGraph().addNode("tools", node_async((state, config) -> Map.of()))
			.addEdge(START, "tools")
			.addEdge("tools", END)
			.compile();
		return new GraphRunnerContext(new OverAllState(), RunnableConfig.builder().build(), graph);
	}

}
