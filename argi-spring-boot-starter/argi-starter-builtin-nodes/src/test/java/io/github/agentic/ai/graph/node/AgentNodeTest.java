/*
 * Copyright 2024-2026 the original author or authors.
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

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentNodeTest {

	private static final ToolCall LOOKUP = new ToolCall("call_1", "lookup", "{}");

	static ChatResponse response(ChatMessage message, List<ToolCall> calls, Usage usage) {
		return new ChatResponse("id", "model", List.of(message), calls,
				calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS, usage, Map.of());
	}

	static ChatResponse answer(String text) {
		return response(ChatMessage.assistant(text), List.of(), new Usage(2L, 1L, 3L));
	}

	static ChatResponse calls(ToolCall... calls) {
		return response(ChatMessage.assistant(null, List.of(calls)), List.of(calls), new Usage(4L, 2L, 6L));
	}

	static Tool tool(String name) {
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition(name, "Lookup", "{}"));
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text("found")));
		return tool;
	}

	static OverAllState state() {
		return new OverAllState(Map.of("messages", List.of(ChatMessage.user("question"))));
	}

	private static Throwable failure(CompletableFuture<?> future) {
		return assertThrows(CompletionException.class, future::join).getCause();
	}

	@Test
	void rendersPromptsInOrderAndPersistsGeneratedUserPrompt() {
		ChatClient client = mock(ChatClient.class);
		List<ChatRequest> requests = new ArrayList<>();
		when(client.call(any())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return CompletableFuture.completedFuture(answer("summary"));
		});
		List<ChatMessage> history = List.of(ChatMessage.user("previous"));
		OverAllState state = new OverAllState(Map.of("messages", history, "topic", "ARGI"));
		Map<String, Object> output = AgentNode.builder()
			.chatClient(client)
			.systemPrompt("Discuss {topic}")
			.userPrompt("Summarize {topic}")
			.options(GenerationOptions.defaults())
			.outputKey("summary")
			.build()
			.apply(state)
			.join();
		assertEquals(List.of(ChatMessage.system("Discuss ARGI"), ChatMessage.user("previous"),
				ChatMessage.user("Summarize ARGI")), requests.get(0).messages());
		assertEquals(List.of(ChatMessage.user("Summarize ARGI"), ChatMessage.assistant("summary")),
				output.get("messages"));
		assertEquals(history, state.value("messages").orElseThrow());
		assertEquals("summary", output.get("summary"));
		assertEquals(new Usage(2L, 1L, 3L), output.get(NodeOutput.TOKEN_USAGE_KEY));
	}

	@Test
	void runsModelToolModelLoopWithInvocationIdentityAndUsage() {
		ChatClient client = mock(ChatClient.class);
		List<ChatRequest> requests = new ArrayList<>();
		when(client.call(any())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return CompletableFuture.completedFuture(requests.size() == 1 ? calls(LOOKUP) : answer("done"));
		});
		Tool tool = tool("lookup");
		OverAllState input = state();
		when(tool.execute(any(), any())).thenAnswer(invocation -> {
			ToolContext context = invocation.getArgument(1);
			assertSame(input, context.attributes().get("state"));
			return CompletableFuture.completedFuture(ToolResult.text("found"));
		});
		Map<String, Object> output = AgentNode.builder()
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.build()
			.apply(input)
			.join();
		assertEquals("done", output.get("agent_output"));
		assertEquals(new Usage(6L, 3L, 9L), output.get(NodeOutput.TOKEN_USAGE_KEY));
		assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL),
				requests.get(1).messages().stream().map(ChatMessage::role).toList());
		assertEquals("call_1", requests.get(1).messages().get(2).toolCallId());
		assertEquals("lookup", requests.get(1).messages().get(2).toolName());
		assertEquals(List.of(LOOKUP), requests.get(1).messages().get(1).toolCalls());
		assertEquals(3, ((List<?>) output.get("messages")).size());
		assertEquals("done", ((ChatResponse) output.get(LlmNode.LLM_RESPONSE_KEY)).text());
		verify(tool).execute(any(), any());
	}

	@Test
	void handlesCallsPresentOnlyOnResponseOrOnlyOnMessage() {
		for (boolean onMessage : List.of(false, true)) {
			ChatClient client = mock(ChatClient.class);
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(
					response(onMessage ? ChatMessage.assistant(null, List.of(LOOKUP)) : ChatMessage.assistant(""),
							onMessage ? List.of() : List.of(LOOKUP), Usage.unknown())));
			Map<String, Object> result = AgentNode.builder()
				.chatClient(client)
				.toolRegistry(new ToolRegistry(List.of(tool("lookup"))))
				.strategy(AgentNode.Strategy.TOOL_CALLING)
				.build()
				.apply(state())
				.join();
			assertEquals(List.of(LOOKUP), ((ChatMessage) ((List<?>) result.get("messages")).get(0)).toolCalls());
			assertEquals("found", result.get("agent_output"));
		}
	}

	@Test
	void returnDirectExecutesEveryCallInOrderWithoutAnotherTurn() {
		for (boolean strategyDirect : List.of(false, true)) {
			ChatClient client = mock(ChatClient.class);
			Tool first = tool("first");
			Tool second = tool("second");
			when(first.returnDirect()).thenReturn(!strategyDirect);
			when(second.returnDirect()).thenReturn(!strategyDirect);
			List<String> executed = new ArrayList<>();
			when(first.execute(any(), any())).thenAnswer(invocation -> {
				executed.add("first");
				return CompletableFuture.completedFuture(ToolResult.text("a"));
			});
			when(second.execute(any(), any())).thenAnswer(invocation -> {
				executed.add("second");
				return CompletableFuture.completedFuture(ToolResult.text("b"));
			});
			when(client.call(any())).thenReturn(CompletableFuture
				.completedFuture(calls(new ToolCall("a", "first", "{}"), new ToolCall("b", "second", "{}"))));
			Map<String, Object> output = AgentNode.builder()
				.chatClient(client)
				.toolRegistry(new ToolRegistry(List.of(first, second)))
				.strategy(strategyDirect ? AgentNode.Strategy.TOOL_CALLING : AgentNode.Strategy.REACT)
				.build()
				.apply(state())
				.join();
			assertEquals("ab", output.get("agent_output"));
			assertEquals(List.of("first", "second"), executed);
			verify(client).call(any());
		}
	}

	@Test
	void mixedReturnDirectToolsContinueAndErrorResultsRemainModelVisible() {
		ChatClient client = mock(ChatClient.class);
		Tool first = tool("first");
		Tool second = tool("second");
		when(first.returnDirect()).thenReturn(true);
		when(second.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.error("missing")));
		AtomicInteger turns = new AtomicInteger();
		when(client.call(any())).thenAnswer(invocation -> {
			if (turns.getAndIncrement() == 0) {
				return CompletableFuture
					.completedFuture(calls(new ToolCall("a", "first", "{}"), new ToolCall("b", "second", "{}")));
			}
			ChatRequest request = invocation.getArgument(0);
			assertEquals(true, request.messages().get(3).metadata().get("tool.error"));
			return CompletableFuture.completedFuture(answer("handled"));
		});
		assertEquals("handled",
				AgentNode.builder()
					.chatClient(client)
					.toolRegistry(new ToolRegistry(List.of(first, second)))
					.build()
					.apply(state())
					.join()
					.get("agent_output"));
		assertEquals(2, turns.get());
	}

	@Test
	void modelRetriesDoNotRepeatAlreadyExecutedTools() {
		ChatClient client = mock(ChatClient.class);
		Tool tool = tool("lookup");
		AtomicInteger attempts = new AtomicInteger();
		List<ChatRequest> requests = new ArrayList<>();
		when(client.call(any())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return switch (attempts.getAndIncrement()) {
				case 0 -> CompletableFuture.completedFuture(calls(LOOKUP));
				case 1 -> CompletableFuture.failedFuture(new IllegalStateException("temporary failure"));
				default -> CompletableFuture.completedFuture(answer("done"));
			};
		});
		Map<String, Object> result = AgentNode.builder()
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.maxRetries(1)
			.maxModelTurns(2)
			.build()
			.apply(state())
			.join();
		assertEquals("done", result.get("agent_output"));
		assertSame(requests.get(1), requests.get(2));
		verify(tool).execute(any(), any());
	}

	@Test
	void exhaustionPreservesOriginalFailureAndDoesNotMutateState() {
		ChatClient client = mock(ChatClient.class);
		IllegalStateException error = new IllegalStateException("provider failed");
		when(client.call(any())).thenThrow(new CompletionException(new CompletionException(error)));
		OverAllState state = state();
		assertSame(error, failure(AgentNode.builder().chatClient(client).maxRetries(2).build().apply(state)));
		verify(client, times(3)).call(any());
		assertEquals(1, ((List<?>) state.value("messages").orElseThrow()).size());
		assertTrue(state.value("agent_output").isEmpty());
	}

	@Test
	void nonRetryableFailuresAndCancellationAreNotRetried() {
		for (RuntimeException error : List.of(new IllegalArgumentException("bad request"),
				new UnsupportedOperationException("unsupported"), new CancellationException("cancelled"))) {
			ChatClient client = mock(ChatClient.class);
			when(client.call(any())).thenReturn(CompletableFuture.failedFuture(error));
			CompletableFuture<?> result = AgentNode.builder().chatClient(client).maxRetries(5).build().apply(state());
			if (error instanceof CancellationException) {
				assertThrows(CancellationException.class, result::join);
			}
			else {
				assertSame(error, failure(result));
			}
			verify(client).call(any());
		}
	}

	@Test
	void toolFailuresAreNotRetriedOrSwallowed() {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(calls(LOOKUP)));
		Tool tool = tool("lookup");
		IllegalStateException error = new IllegalStateException("tool failed");
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.failedFuture(error));
		assertSame(error,
				failure(AgentNode.builder()
					.chatClient(client)
					.toolRegistry(new ToolRegistry(List.of(tool)))
					.maxRetries(5)
					.build()
					.apply(state())));
		verify(tool).execute(any(), any());
		verify(client).call(any());
	}

	@Test
	void stopsAtModelTurnLimitWithoutStackOverflow() {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(calls(LOOKUP)));
		Tool tool = tool("lookup");
		Throwable failure = failure(AgentNode.builder()
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.maxModelTurns(300)
			.maxRetries(2)
			.build()
			.apply(state()));
		assertInstanceOf(IllegalStateException.class, failure);
		assertTrue(failure.getMessage().contains("maxModelTurns=300"));
		verify(client, times(300)).call(any());
		verify(tool, times(300)).execute(any(), any());
	}

	@Test
	void cancellationReachesActiveModelAndToolAndStopsRemainingWork() {
		ChatClient client = mock(ChatClient.class);
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		when(client.call(any())).thenReturn(pending);
		CompletableFuture<?> result = AgentNode.builder().chatClient(client).maxRetries(4).build().apply(state());
		result.cancel(true);
		assertTrue(pending.isCancelled());
		verify(client).call(any());

		Tool first = tool("first");
		Tool second = tool("second");
		CompletableFuture<ToolResult> pendingTool = new CompletableFuture<>();
		when(first.execute(any(), any())).thenReturn(pendingTool);
		when(client.call(any())).thenReturn(CompletableFuture
			.completedFuture(calls(new ToolCall("a", "first", "{}"), new ToolCall("b", "second", "{}"))));
		CompletableFuture<?> toolResult = AgentNode.builder()
			.chatClient(client)
			.toolRegistry(new ToolRegistry(List.of(first, second)))
			.build()
			.apply(state());
		toolResult.cancel(true);
		assertTrue(pendingTool.isCancelled());
		verify(second, never()).execute(any(), any());
		verify(client, times(2)).call(any());
	}

	@Test
	void overlappingInvocationsKeepHistoryAndUsageIsolated() {
		ChatClient client = mock(ChatClient.class);
		List<ChatRequest> requests = new ArrayList<>();
		List<CompletableFuture<ChatResponse>> pending = new ArrayList<>();
		when(client.call(any())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			CompletableFuture<ChatResponse> response = new CompletableFuture<>();
			pending.add(response);
			return response;
		});
		AgentNode node = AgentNode.builder().chatClient(client).userPrompt("{name}").build();
		CompletableFuture<Map<String, Object>> first = node.apply(new OverAllState(Map.of("name", "first")));
		CompletableFuture<Map<String, Object>> second = node.apply(new OverAllState(Map.of("name", "second")));
		pending.get(1).complete(answer("second answer"));
		assertFalse(first.isDone());
		pending.get(0).complete(answer("first answer"));
		assertEquals("first", requests.get(0).messages().get(0).text());
		assertEquals("second", requests.get(1).messages().get(0).text());
		assertEquals("first answer", first.join().get("agent_output"));
		assertEquals("second answer", second.join().get("agent_output"));
	}

	@Test
	void missingUsageStaysUnknownAndLargeCountsAreNotTruncated() {
		for (boolean unknown : List.of(false, true)) {
			ChatClient client = mock(ChatClient.class);
			ChatResponse initial = response(ChatMessage.assistant(null, List.of(LOOKUP)), List.of(LOOKUP),
					new Usage(3_000_000_000L, 2L, 3_000_000_002L));
			ChatResponse last = response(ChatMessage.assistant("done"), List.of(),
					unknown ? Usage.unknown() : new Usage(1L, 1L, 2L));
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(initial),
					CompletableFuture.completedFuture(last));
			Map<String, Object> output = AgentNode.builder()
				.chatClient(client)
				.toolRegistry(new ToolRegistry(List.of(tool("lookup"))))
				.build()
				.apply(state())
				.join();
			assertEquals(unknown ? Usage.unknown() : new Usage(3_000_000_001L, 3L, 3_000_000_004L),
					output.get(NodeOutput.TOKEN_USAGE_KEY));
		}
	}

	@Test
	void rejectsMalformedResponsesBeforeAnyToolSideEffect() {
		ToolCall other = new ToolCall("other", "lookup", "{}");
		List<ChatResponse> malformed = List.of(new ChatResponse(null, null, List.of(), List.of(), null, null, Map.of()),
				response(ChatMessage.user("wrong role"), List.of(), Usage.unknown()),
				response(ChatMessage.assistant(null, List.of(LOOKUP)), List.of(other), Usage.unknown()),
				calls(LOOKUP, LOOKUP), calls(LOOKUP, new ToolCall("unknown", "missing", "{}")));
		for (ChatResponse response : malformed) {
			ChatClient client = mock(ChatClient.class);
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response));
			Tool tool = tool("lookup");
			failure(AgentNode.builder()
				.chatClient(client)
				.toolRegistry(new ToolRegistry(List.of(tool)))
				.maxRetries(3)
				.build()
				.apply(state()));
			verify(tool, never()).execute(any(), any());
			verify(client).call(any());
		}
	}

	@Test
	void rejectsEmptyResponseAndMalformedHistory() {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(null));
		assertInstanceOf(IllegalStateException.class,
				failure(AgentNode.builder().chatClient(client).build().apply(state())));
		AgentNode node = AgentNode.builder().chatClient(client).build();
		assertInstanceOf(IllegalArgumentException.class, failure(node.apply(new OverAllState(Map.of()))));
		assertInstanceOf(IllegalArgumentException.class,
				failure(node.apply(new OverAllState(Map.of("messages", "invalid")))));
		assertInstanceOf(IllegalArgumentException.class,
				failure(node.apply(new OverAllState(Map.of("messages", List.of("invalid"))))));
		assertInstanceOf(NullPointerException.class, failure(node.apply(null)));
		verify(client).call(any());
	}

	@Test
	void validatesConfigurationAndReservedOutputKeys() {
		ChatClient client = mock(ChatClient.class);
		assertThrows(NullPointerException.class, () -> AgentNode.builder().build());
		assertThrows(NullPointerException.class,
				() -> AgentNode.builder().chatClient(client).toolRegistry(null).build());
		assertThrows(NullPointerException.class, () -> AgentNode.builder().chatClient(client).strategy(null).build());
		assertThrows(NullPointerException.class, () -> AgentNode.builder().chatClient(client).options(null).build());
		assertThrows(NullPointerException.class,
				() -> AgentNode.builder().chatClient(client).systemPrompt(null).build());
		assertThrows(NullPointerException.class, () -> AgentNode.builder().chatClient(client).userPrompt(null).build());
		assertThrows(IllegalArgumentException.class,
				() -> AgentNode.builder().chatClient(client).maxModelTurns(0).build());
		assertThrows(IllegalArgumentException.class,
				() -> AgentNode.builder().chatClient(client).maxRetries(-1).build());
		for (String key : List.of(" ", "messages", LlmNode.LLM_RESPONSE_KEY, NodeOutput.TOKEN_USAGE_KEY)) {
			assertThrows(IllegalArgumentException.class,
					() -> AgentNode.builder().chatClient(client).outputKey(key).build());
		}
	}

}
