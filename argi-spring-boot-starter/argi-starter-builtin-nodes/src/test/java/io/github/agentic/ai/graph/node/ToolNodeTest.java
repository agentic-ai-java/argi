/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.OverAllState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolNodeTest {

	private static final ToolCall FIRST = new ToolCall("call_1", "calculator", "{\"a\":1}");

	private static final ToolCall SECOND = new ToolCall("call_2", "calculator", "{\"a\":2}");

	private static Tool tool() {
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition("calculator", "Calculate", "{}"));
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text("3")));
		return tool;
	}

	private static ChatMessage assistant(ToolCall... calls) {
		return ChatMessage.assistant("", List.of(calls));
	}

	@Test
	void executesOrderedResultsWithIdentitiesContextAndCustomOutput() {
		Tool tool = tool();
		OverAllState state = new OverAllState(Map.of("custom", assistant(FIRST, SECOND)));
		ToolNode node = ToolNode.builder()
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.llmResponseKey("custom")
			.outputKey("result")
			.build();
		Map<String, Object> result = node.apply(state).join();
		@SuppressWarnings("unchecked")
		List<ChatMessage> messages = (List<ChatMessage>) result.get("messages");
		assertEquals(List.of("call_1", "call_2"), messages.stream().map(ChatMessage::toolCallId).toList());
		assertEquals(List.of("calculator", "calculator"), messages.stream().map(ChatMessage::toolName).toList());
		assertEquals(List.of("3", "3"), messages.stream().map(ChatMessage::text).toList());
		assertSame(messages, result.get("result"));
		ArgumentCaptor<ToolContext> context = ArgumentCaptor.forClass(ToolContext.class);
		verify(tool, times(2)).execute(any(), context.capture());
		assertSame(state, context.getValue().attributes().get("state"));
	}

	@Test
	void readsChatResponseCallsWithoutDuplicatingMessageCalls() {
		Tool tool = tool();
		ChatResponse response = new ChatResponse(null, null, List.of(assistant(FIRST)), List.of(FIRST), null, null,
				null);
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of(tool))).build();
		assertEquals(1,
				((List<?>) node.apply(new OverAllState(Map.of(LlmNode.LLM_RESPONSE_KEY, response)))
					.join()
					.get("messages")).size());
		verify(tool, times(1)).execute(any(), any());
	}

	@Test
	void readsResponseMessageCallsAndFinalHistoryMessage() {
		Tool tool = tool();
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of(tool))).build();
		ChatResponse response = new ChatResponse(null, null, List.of(assistant(FIRST)), List.of(), null, null, null);
		node.apply(new OverAllState(Map.of(LlmNode.LLM_RESPONSE_KEY, response))).join();
		node.apply(new OverAllState(Map.of("messages", List.of(ChatMessage.user("question"), assistant(SECOND)))))
			.join();
		node.apply(new OverAllState(Map.of("messages", assistant(FIRST)))).join();
		verify(tool, times(3)).execute(any(), any());
	}

	@Test
	void emptyCallsAreANoOpAndErrorResultsRemainModelVisible() {
		Tool tool = tool();
		when(tool.execute(any(), any()))
			.thenReturn(CompletableFuture.completedFuture(ToolResult.error("invalid input")));
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of(tool))).outputKey(" ").build();
		var result = node.apply(new OverAllState(Map.of("messages", assistant(FIRST)))).join();
		ChatMessage message = (ChatMessage) ((List<?>) result.get("messages")).get(0);
		assertEquals(true, message.metadata().get("tool.error"));
		assertEquals("invalid input", message.text());
		assertEquals(Map.of("messages", List.of()),
				node.apply(new OverAllState(Map.of("messages", assistant()))).join());
	}

	@Test
	void cancellationStopsActiveToolAndDoesNotStartTheNextTool() {
		Tool tool = tool();
		CompletableFuture<ToolResult> active = new CompletableFuture<>();
		when(tool.execute(any(), any())).thenReturn(active);
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of(tool))).build();
		var result = node.apply(new OverAllState(Map.of("messages", assistant(FIRST, SECOND))));
		assertFalse(result.isDone());
		verify(tool, times(1)).execute(any(), any());
		result.cancel(true);
		assertTrue(active.isCancelled());
		verify(tool, times(1)).execute(any(), any());
	}

	@Test
	void overlappingInvocationsHaveSeparateFuturesAndContexts() {
		Tool tool = tool();
		CompletableFuture<ToolResult> leftSource = new CompletableFuture<>();
		CompletableFuture<ToolResult> rightSource = new CompletableFuture<>();
		when(tool.execute(any(), any())).thenReturn(leftSource, rightSource);
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of(tool))).build();
		var left = node.apply(new OverAllState(Map.of("messages", assistant(FIRST))));
		var right = node.apply(new OverAllState(Map.of("messages", assistant(SECOND))));
		rightSource.complete(ToolResult.text("right"));
		leftSource.complete(ToolResult.text("left"));
		assertEquals("left", ((ChatMessage) ((List<?>) left.join().get("messages")).get(0)).text());
		assertEquals("right", ((ChatMessage) ((List<?>) right.join().get("messages")).get(0)).text());
	}

	@Test
	void unknownToolsAndExecutionFailuresFailTheWholeNode() {
		Tool tool = tool();
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of(tool))).build();
		assertThrows(CompletionException.class,
				() -> node
					.apply(new OverAllState(Map.of("messages", assistant(new ToolCall("unknown", "unknown", "{}")))))
					.join());
		when(tool.execute(any(), any())).thenThrow(new IllegalStateException("failed"));
		assertEquals("failed",
				assertThrows(CompletionException.class,
						() -> node.apply(new OverAllState(Map.of("messages", assistant(FIRST, SECOND)))).join())
					.getCause()
					.getMessage());
		verify(tool, times(1)).execute(any(), any());
	}

	@Test
	void rejectsInvalidStateAndBuilderConfigurations() {
		ToolNode node = ToolNode.builder().toolRegistry(new ToolRegistry(List.of())).build();
		for (Map<String, Object> data : List.of(Map.<String, Object>of(), Map.<String, Object>of("messages", List.of()),
				Map.<String, Object>of("messages", ChatMessage.user("not assistant")),
				Map.<String, Object>of(LlmNode.LLM_RESPONSE_KEY, "invalid"))) {
			assertThrows(CompletionException.class, () -> node.apply(new OverAllState(data)).join());
		}
		assertThrows(CompletionException.class, () -> node.apply(null).join());
		assertThrows(NullPointerException.class, () -> ToolNode.builder().build());
		assertThrows(IllegalArgumentException.class,
				() -> ToolNode.builder().toolRegistry(new ToolRegistry(List.of())).llmResponseKey(" ").build());
		assertThrows(IllegalArgumentException.class,
				() -> ToolNode.builder()
					.toolRegistry(new ToolRegistry(List.of()))
					.outputKey(LlmNode.LLM_RESPONSE_KEY)
					.build());
	}

}
