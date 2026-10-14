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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeutralChatGraphIntegrationTest {

	@TempDir
	Path checkpointFolder;

	private static Map<String, KeyStrategy> strategies() {
		Map<String, KeyStrategy> strategies = new HashMap<>();
		strategies.put("messages", new AppendStrategy());
		strategies.put(LlmNode.LLM_RESPONSE_KEY, new ReplaceStrategy());
		strategies.put("observed", new ReplaceStrategy());
		return strategies;
	}

	@Test
	void reportsPerNodeUsageWithoutPersistingOrLeakingIt() throws Exception {
		for (boolean streaming : List.of(false, true)) {
			Usage usage = new Usage(3_000_000_000L, 9L, 3_000_000_009L);
			ChatResponse response = new ChatResponse("usage_response", "model",
					List.of(ChatMessage.assistant("answer")), List.of(), FinishReason.STOP, usage, Map.of());
			ChatClient client = mock(ChatClient.class);
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response));
			when(client.stream(any())).thenReturn(JdkFlowAdapter
				.publisherToFlowPublisher(Flux.just(ChatEvent.textDelta("answer"), ChatEvent.response(response))));
			FileSystemSaver saver = FileSystemSaver.builder()
				.targetFolder(checkpointFolder.resolve("usage_" + streaming))
				.build();
			CompiledGraph graph = new StateGraph(NeutralChatGraphIntegrationTest::strategies)
				.addNode("model", LlmNode.builder().chatClient(client).stream(streaming).build())
				.addNode("next", node_async(state -> Map.of("observed", "done")))
				.addEdge(START, "model")
				.addEdge("model", "next")
				.addEdge("next", END)
				.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
			RunnableConfig config = RunnableConfig.builder().threadId("usage_" + streaming).build();
			List<NodeOutput> outputs = graph.stream(Map.of("messages", List.of(ChatMessage.user("hello"))), config)
				.collectList()
				.block(Duration.ofSeconds(5));

			assertTrue(outputs.stream()
				.anyMatch(output -> "model".equals(output.node()) && usage.equals(output.tokenUsage())));
			assertTrue(outputs.stream()
				.filter(output -> "next".equals(output.node()))
				.allMatch(output -> Usage.unknown().equals(output.tokenUsage())));
			assertTrue(outputs.stream().anyMatch(output -> "next".equals(output.node())));
			assertTrue(outputs.stream().allMatch(output -> output.state().value(NodeOutput.TOKEN_USAGE_KEY).isEmpty()));
			assertTrue(graph.getState(config).state().value(NodeOutput.TOKEN_USAGE_KEY).isEmpty());
			assertEquals(usage,
					((ChatResponse) graph.getState(config).state().value(LlmNode.LLM_RESPONSE_KEY).orElseThrow())
						.usage());
		}
	}

	@Test
	void resumesPersistedToolTurnWithANewSaverAndGraph() throws Exception {
		for (boolean streaming : List.of(false, true)) {
			Path folder = checkpointFolder.resolve(Boolean.toString(streaming));
			ChatClient client = mock(ChatClient.class);
			ToolCall call = new ToolCall("persisted_call", "lookup", "{\"query\":\"argi\"}");
			ChatResponse response = new ChatResponse("persisted_response", "model",
					List.of(ChatMessage.assistant("", List.of(call))), List.of(call), FinishReason.TOOL_CALLS,
					new Usage(12L, 4L, 16L), Map.of("provider", "test"));
			AtomicInteger turns = new AtomicInteger();
			List<ChatRequest> requests = new ArrayList<>();
			java.util.function.Function<ChatRequest, ChatResponse> model = request -> {
				requests.add(request);
				return turns.getAndIncrement() == 0 ? response : LlmNodeTest.response("resumed");
			};
			when(client.call(any()))
				.thenAnswer(invocation -> CompletableFuture.completedFuture(model.apply(invocation.getArgument(0))));
			when(client.stream(any())).thenAnswer(invocation -> JdkFlowAdapter.publisherToFlowPublisher(
					Flux.defer(() -> Flux.just(ChatEvent.response(model.apply(invocation.getArgument(0)))))));
			Tool tool = mock(Tool.class);
			when(tool.definition()).thenReturn(new ToolDefinition("lookup", "Lookup", "{}"));
			when(tool.execute(any(), any()))
				.thenReturn(CompletableFuture.completedFuture(ToolResult.error("not found")));
			ToolRegistry registry = new ToolRegistry(List.of(tool));
			LlmNode llm = LlmNode.builder().chatClient(client).toolRegistry(registry).stream(streaming).build();
			ToolNode tools = ToolNode.builder().toolRegistry(registry).build();
			StateGraph definition = new StateGraph(NeutralChatGraphIntegrationTest::strategies).addNode("model", llm)
				.addNode("tools", tools)
				.addEdge(START, "model")
				.addConditionalEdges("model", state -> CompletableFuture.completedFuture(
						((ChatResponse) state.value(LlmNode.LLM_RESPONSE_KEY).orElseThrow()).toolCalls().isEmpty()
								? "done" : "tools"),
						Map.of("done", END, "tools", "tools"))
				.addEdge("tools", "model");
			FileSystemSaver firstSaver = FileSystemSaver.builder().targetFolder(folder).build();
			CompiledGraph initial = definition.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(firstSaver).build())
				.interruptAfter("model")
				.build());
			ChatMessage question = new ChatMessage(ChatRole.USER,
					List.of(ContentPart.text("question"),
							ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2, 3 })),
					Map.of("source", "test"));
			RunnableConfig config = RunnableConfig.builder().threadId("persisted-tool-turn").build();
			initial.stream(Map.of("messages", List.of(question)), config).collectList().block(Duration.ofSeconds(5));
			assertEquals(1, turns.get());
			verify(tool, times(0)).execute(any(), any());

			// A fresh saver has no cached state: this read must deserialize the
			// checkpoint file.
			FileSystemSaver restartedSaver = FileSystemSaver.builder().targetFolder(folder).build();
			Map<String, Object> persisted = restartedSaver.get(config).orElseThrow().getState();
			assertEquals(response, persisted.get(LlmNode.LLM_RESPONSE_KEY));
			ChatMessage restoredQuestion = (ChatMessage) ((List<?>) persisted.get("messages")).get(0);
			assertEquals("test", restoredQuestion.metadata().get("source"));
			assertArrayEquals(new byte[] { 1, 2, 3 }, restoredQuestion.content().get(1).data());
			CompiledGraph restarted = definition.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(restartedSaver).build())
				.build());
			OverAllState state = restarted
				.invoke(Map.of(), RunnableConfig.builder().threadId("persisted-tool-turn").resume().build())
				.orElseThrow();

			assertEquals(2, turns.get(), "The interrupted model turn must not be repeated");
			verify(tool, times(1)).execute(any(), any());
			List<ChatMessage> history = requests.get(1).messages();
			assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL),
					history.stream().map(ChatMessage::role).toList());
			assertEquals(call, history.get(1).toolCalls().get(0));
			assertEquals("persisted_call", history.get(2).toolCallId());
			assertEquals(true, history.get(2).metadata().get("tool.error"));
			assertEquals("resumed", ((ChatResponse) state.value(LlmNode.LLM_RESPONSE_KEY).orElseThrow()).text());
		}
	}

	@Test
	void modelToolModelLoopUsesNeutralMessagesAcrossGraphStateCopies() throws Exception {
		for (boolean streaming : List.of(false, true)) {
			ChatClient client = mock(ChatClient.class);
			AtomicInteger turns = new AtomicInteger();
			List<ChatRequest> requests = new ArrayList<>();
			ToolCall call = new ToolCall("lookup_1", "lookup", "{}");
			ChatResponse toolResponse = new ChatResponse("tool", "model",
					List.of(ChatMessage.assistant("", List.of(call))), List.of(call), null, null, Map.of());
			java.util.function.Function<ChatRequest, ChatResponse> model = request -> {
				requests.add(request);
				return turns.getAndIncrement() == 0 ? toolResponse : LlmNodeTest.response("done");
			};
			when(client.call(any()))
				.thenAnswer(invocation -> CompletableFuture.completedFuture(model.apply(invocation.getArgument(0))));
			when(client.stream(any())).thenAnswer(invocation -> JdkFlowAdapter.publisherToFlowPublisher(
					Flux.defer(() -> Flux.just(ChatEvent.response(model.apply(invocation.getArgument(0)))))));
			Tool tool = mock(Tool.class);
			when(tool.definition()).thenReturn(new ToolDefinition("lookup", "Lookup", "{}"));
			when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text("found")));
			ToolRegistry registry = new ToolRegistry(List.of(tool));
			LlmNode llm = LlmNode.builder().chatClient(client).toolRegistry(registry).stream(streaming).build();
			ToolNode tools = ToolNode.builder().toolRegistry(registry).build();
			CompiledGraph graph = new StateGraph(NeutralChatGraphIntegrationTest::strategies).addNode("model", llm)
				.addNode("tools", tools)
				.addEdge(START, "model")
				.addConditionalEdges("model", state -> CompletableFuture.completedFuture(
						((ChatResponse) state.value(LlmNode.LLM_RESPONSE_KEY).orElseThrow()).toolCalls().isEmpty()
								? "done" : "tools"),
						Map.of("done", END, "tools", "tools"))
				.addEdge("tools", "model")
				.compile();

			OverAllState state = graph.invoke(Map.of("messages", List.of(ChatMessage.user("question")))).orElseThrow();

			assertEquals(2, turns.get());
			verify(tool, times(1)).execute(any(), any());
			assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL),
					requests.get(1).messages().stream().map(ChatMessage::role).toList());
			assertEquals("lookup_1", requests.get(1).messages().get(2).toolCallId());
			assertEquals("found", requests.get(1).messages().get(2).text());
			assertEquals("done", ((ChatResponse) state.value(LlmNode.LLM_RESPONSE_KEY).orElseThrow()).text());
			assertEquals(4, ((List<?>) state.value("messages").orElseThrow()).size());
		}
	}

	@Test
	void streamedEventsRetainIdentityAndCommitFullResponseBeforeNextNode() throws Exception {
		ChatClient client = mock(ChatClient.class);
		ChatResponse response = LlmNodeTest.response("answer");
		when(client.stream(any())).thenReturn(JdkFlowAdapter.publisherToFlowPublisher(
				Flux.just(ChatEvent.textDelta("an"), ChatEvent.textDelta("swer"), ChatEvent.response(response))));
		CompiledGraph graph = new StateGraph(NeutralChatGraphIntegrationTest::strategies)
			.addNode("model", LlmNode.builder().chatClient(client).stream(true).build())
			.addNode("next",
					node_async(state -> Map.of("observed",
							((ChatResponse) state.value(LlmNode.LLM_RESPONSE_KEY).orElseThrow()).text())))
			.addEdge(START, "model")
			.addEdge("model", "next")
			.addEdge("next", END)
			.compile();
		List<GraphResponse<NodeOutput>> responses = graph
			.graphResponseStream(Map.of("messages", List.of(ChatMessage.user("hello"))),
					RunnableConfig.builder().build())
			.collectList()
			.block(Duration.ofSeconds(5));
		List<StreamingOutput<?>> chunks = responses.stream()
			.filter(responseItem -> responseItem.getOutput() != null)
			.map(responseItem -> responseItem.getOutput().join())
			.filter(StreamingOutput.class::isInstance)
			.<StreamingOutput<?>>map(output -> (StreamingOutput<?>) output)
			.filter(output -> output.getOriginData() instanceof ChatEvent)
			.toList();
		assertEquals(3, chunks.size());
		assertTrue(chunks.stream().allMatch(chunk -> "model".equals(chunk.node())));
		assertEquals(List.of(ChatEvent.textDelta("an"), ChatEvent.textDelta("swer"), ChatEvent.response(response)),
				chunks.stream().map(StreamingOutput::getOriginData).toList());
		assertEquals("answer", chunks.stream().map(StreamingOutput::text).reduce("", String::concat));
		assertTrue(chunks.get(2).messages().isEmpty());
		assertTrue(responses.stream()
			.filter(item -> item.getOutput() != null)
			.map(item -> item.getOutput().join())
			.anyMatch(output -> "answer".equals(output.state().value("observed", ""))));
	}

	@Test
	void graphFailureDoesNotRunNextNodeOrSubmitPartialResponse() throws Exception {
		ChatClient client = mock(ChatClient.class);
		when(client.stream(any())).thenReturn(JdkFlowAdapter.publisherToFlowPublisher(
				Flux.just(ChatEvent.textDelta("partial"), ChatEvent.response(LlmNodeTest.response("not committed")))
					.concatWith(Flux.error(new IllegalStateException("provider failed")))));
		AtomicInteger nextCalls = new AtomicInteger();
		CompiledGraph graph = new StateGraph(NeutralChatGraphIntegrationTest::strategies)
			.addNode("model", LlmNode.builder().chatClient(client).stream(true).build())
			.addNode("next", node_async(state -> {
				nextCalls.incrementAndGet();
				return Map.of("observed", "should not run");
			}))
			.addEdge(START, "model")
			.addEdge("model", "next")
			.addEdge("next", END)
			.compile();
		List<GraphResponse<NodeOutput>> responses = graph
			.graphResponseStream(Map.of("messages", List.of(ChatMessage.user("hello"))),
					RunnableConfig.builder().build())
			.collectList()
			.block(Duration.ofSeconds(5));
		assertEquals(0, nextCalls.get());
		assertTrue(responses.stream().anyMatch(GraphResponse::isError));
		for (var item : responses) {
			if (item.getOutput() != null && !item.getOutput().isCompletedExceptionally()) {
				assertTrue(item.getOutput().join().state().value(LlmNode.LLM_RESPONSE_KEY).isEmpty());
			}
		}
	}

}
