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

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.agentic.ai.adapter.agentscope.AgentScopeChatClientAdapter;
import io.github.agentic.ai.adapter.googleadk.GoogleAdkChatClientAdapter;
import io.github.agentic.ai.adapter.langchain4j.LangChain4jChatClientAdapter;
import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import io.reactivex.rxjava3.core.Flowable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies real adapters against deterministic native SDK models, without network access.
 */
class AdapterGraphIntegrationTest {

	enum Framework {

		SPRING_AI, LANGCHAIN4J, GOOGLE_ADK, AGENTSCOPE

	}

	@TempDir
	Path checkpointFolder;

	static Stream<Arguments> frameworksAndModes() {
		return Stream.of(Framework.values())
			.flatMap(framework -> Stream.of(false, true).map(streaming -> Arguments.of(framework, streaming)));
	}

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("frameworksAndModes")
	void nativeModelToolModelLoopPreservesHistoryUsageAndCheckpoint(Framework framework, boolean streaming)
			throws Exception {
		NativeTurn turn = new NativeTurn();
		Tool tool = tool();
		ToolRegistry registry = new ToolRegistry(List.of(tool));
		ChatClient client = client(framework, turn);
		Path folder = checkpointFolder.resolve(framework + "_" + streaming);
		RunnableConfig config = RunnableConfig.builder().threadId("adapter-loop").build();
		CompiledGraph graph = graph(client, registry, streaming, folder);

		List<NodeOutput> outputs = graph.stream(input(), config).collectList().block(Duration.ofSeconds(10));

		assertNotNull(outputs);
		assertEquals(2, turn.calls.get());
		verify(tool).execute(any(), any());
		List<NodeOutput> modelOutputs = outputs.stream()
			.filter(output -> "model".equals(output.node()))
			.filter(output -> !(output instanceof StreamingOutput<?> chunk) || chunk.getOriginData() == null
					|| chunk.getOriginData() instanceof ChatEvent event && event.type() == ChatEvent.Type.RESPONSE)
			.toList();
		assertEquals(2, modelOutputs.size());
		Usage expectedUsage = framework == Framework.GOOGLE_ADK ? Usage.unknown() : new Usage(10L, 5L, 15L);
		assertTrue(modelOutputs.stream().allMatch(output -> expectedUsage.equals(output.tokenUsage())));
		assertTrue(outputs.stream().allMatch(output -> output.state().value(NodeOutput.TOKEN_USAGE_KEY).isEmpty()));
		if (streaming) {
			List<ChatEvent> events = outputs.stream()
				.filter(StreamingOutput.class::isInstance)
				.map(output -> ((StreamingOutput<?>) output).getOriginData())
				.filter(ChatEvent.class::isInstance)
				.map(ChatEvent.class::cast)
				.toList();
			assertEquals(2, events.stream().filter(event -> event.type() == ChatEvent.Type.RESPONSE).count());
			assertEquals("done",
					events.stream()
						.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
						.map(ChatEvent::text)
						.reduce("", String::concat));
		}
		// Re-open the saver to test disk deserialization, not its in-memory cache.
		Map<String, Object> persisted = FileSystemSaver.builder()
			.targetFolder(folder)
			.build()
			.get(config)
			.orElseThrow()
			.getState();
		List<?> history = (List<?>) persisted.get("messages");
		assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
				history.stream().map(ChatMessage.class::cast).map(ChatMessage::role).toList());
		ChatMessage result = (ChatMessage) history.get(2);
		assertEquals("lookup_1", result.toolCallId());
		assertEquals("lookup", result.toolName());
		assertEquals("found", result.text());
		ChatResponse response = (ChatResponse) persisted.get(LlmNode.LLM_RESPONSE_KEY);
		assertEquals("done", response.text());
		assertEquals(expectedUsage, response.usage());
		assertFalse(persisted.containsKey(NodeOutput.TOKEN_USAGE_KEY));
	}

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("frameworksAndModes")
	void nativeFailureNeverCommitsPartialModelResponseOrRunsTools(Framework framework, boolean streaming)
			throws Exception {
		NativeTurn turn = new NativeTurn();
		turn.fail = true;
		Tool tool = tool();
		RunnableConfig config = RunnableConfig.builder().threadId("adapter-failure").build();
		Path folder = checkpointFolder.resolve(framework + "_" + streaming);
		CompiledGraph graph = graph(client(framework, turn), new ToolRegistry(List.of(tool)), streaming, folder);

		List<GraphResponse<NodeOutput>> outputs = graph.graphResponseStream(input(), config)
			.collectList()
			.block(Duration.ofSeconds(10));

		assertNotNull(outputs);
		assertTrue(outputs.stream().anyMatch(GraphResponse::isError));
		assertEquals(1, turn.calls.get());
		verify(tool, never()).execute(any(), any());
		Map<String, Object> persisted = FileSystemSaver.builder()
			.targetFolder(folder)
			.build()
			.get(config)
			.orElseThrow()
			.getState();
		assertEquals(input().get("messages"), persisted.get("messages"));
		assertFalse(persisted.containsKey(LlmNode.LLM_RESPONSE_KEY));
	}

	@ParameterizedTest
	@EnumSource(Framework.class)
	void graphCancellationReachesNativeStreamWithoutExecutingTools(Framework framework) throws Exception {
		NativeTurn turn = new NativeTurn();
		turn.wait = true;
		Tool tool = tool();
		CompiledGraph graph = graph(client(framework, turn), new ToolRegistry(List.of(tool)), true, checkpointFolder);
		var subscription = graph.stream(input(), RunnableConfig.builder().threadId("cancelled").build()).subscribe();
		try {
			assertTrue(turn.started.await(5, TimeUnit.SECONDS));
		}
		finally {
			subscription.dispose();
		}
		assertTrue(turn.cancelled.await(5, TimeUnit.SECONDS));
		verify(tool, never()).execute(any(), any());
	}

	static Map<String, Object> input() {
		return Map.of("messages", List.of(ChatMessage.user("question")));
	}

	static Map<String, KeyStrategy> strategies() {
		return Map.of("messages", new AppendStrategy(), LlmNode.LLM_RESPONSE_KEY, new ReplaceStrategy());
	}

	static CompiledGraph graph(ChatClient client, ToolRegistry registry, boolean streaming, Path folder)
			throws Exception {
		StateGraph definition = new StateGraph(AdapterGraphIntegrationTest::strategies)
			.addNode("model", LlmNode.builder().chatClient(client).toolRegistry(registry).stream(streaming).build())
			.addNode("tools", ToolNode.builder().toolRegistry(registry).build())
			.addEdge(START, "model")
			.addConditionalEdges("model",
					state -> CompletableFuture.completedFuture(
							((ChatResponse) state.value(LlmNode.LLM_RESPONSE_KEY).orElseThrow()).toolCalls().isEmpty()
									? "done" : "tools"),
					Map.of("done", END, "tools", "tools"))
			.addEdge("tools", "model");
		return definition.compile(CompileConfig.builder()
			.recursionLimit(8)
			.saverConfig(SaverConfig.builder().register(FileSystemSaver.builder().targetFolder(folder).build()).build())
			.build());
	}

	private static Tool tool() {
		Tool tool = mock(Tool.class);
		when(tool.definition())
			.thenReturn(new ToolDefinition("lookup", "Lookup", "{\"type\":\"object\",\"properties\":{}}"));
		when(tool.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ToolResult.text("found")));
		return tool;
	}

	private static ChatClient client(Framework framework, NativeTurn turn) {
		return switch (framework) {
			case SPRING_AI -> new SpringAiChatClientAdapter(new SpringModel(turn));
			case LANGCHAIN4J -> new LangChain4jChatClientAdapter(new LangChainModel(turn), new LangChainStream(turn));
			case GOOGLE_ADK -> new GoogleAdkChatClientAdapter(new AdkModel(turn),
					new io.github.agentic.ai.api.chat.ChatClientCapabilities(true, true, true, false, true, true));
			case AGENTSCOPE -> new AgentScopeChatClientAdapter(new ScopeModel(turn));
		};
	}

	private static final class NativeTurn {

		private final AtomicInteger calls = new AtomicInteger();

		private final CountDownLatch started = new CountDownLatch(1);

		private final CountDownLatch cancelled = new CountDownLatch(1);

		private boolean fail;

		private boolean wait;

		private <T> Flux<T> chunks(Flux<T> response) {
			Flux<T> result = wait ? Flux.never() : response;
			if (fail) {
				result = result.concatWith(Flux.error(new IllegalStateException("native model failure")));
			}
			return result.doOnSubscribe(ignored -> started.countDown()).doOnCancel(cancelled::countDown);
		}

	}

	private static final class SpringModel implements org.springframework.ai.chat.model.ChatModel {

		private final NativeTurn turn;

		private SpringModel(NativeTurn turn) {
			this.turn = turn;
		}

		@Override
		public ToolCallingChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		private org.springframework.ai.chat.model.ChatResponse response(Prompt request) {
			int index = turn.calls.getAndIncrement();
			var options = (ToolCallingChatOptions) request.getOptions();
			assertEquals("lookup", options.getToolCallbacks().get(0).getToolDefinition().name());
			assertThrows(IllegalStateException.class, () -> options.getToolCallbacks().get(0).call("{}"));
			if (index > 0) {
				var result = ((ToolResponseMessage) request.getInstructions().get(2)).getResponses().get(0);
				assertEquals("lookup_1", result.id());
				assertEquals("lookup", result.name());
				assertEquals("found", result.responseData());
			}
			AssistantMessage message = index == 0 ? AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("lookup_1", "function", "lookup", "{}")))
				.build() : new AssistantMessage("done");
			return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(message)),
					ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());
		}

		@Override
		public org.springframework.ai.chat.model.ChatResponse call(Prompt request) {
			return turn.chunks(Flux.just(response(request))).single().block(Duration.ofSeconds(5));
		}

		@Override
		public Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt request) {
			return turn.chunks(Flux.just(response(request)));
		}

	}

	private static dev.langchain4j.model.chat.response.ChatResponse langChainResponse(NativeTurn turn,
			dev.langchain4j.model.chat.request.ChatRequest request) {
		int index = turn.calls.getAndIncrement();
		assertEquals("lookup", request.toolSpecifications().get(0).name());
		if (index > 0) {
			var result = (ToolExecutionResultMessage) request.messages().get(2);
			assertEquals("lookup_1", result.id());
			assertEquals("lookup", result.toolName());
			assertEquals("found", result.text());
		}
		AiMessage message = index == 0
				? AiMessage.from(ToolExecutionRequest.builder().id("lookup_1").name("lookup").arguments("{}").build())
				: AiMessage.from("done");
		return dev.langchain4j.model.chat.response.ChatResponse.builder()
			.aiMessage(message)
			.tokenUsage(new TokenUsage(10, 5, 15))
			.build();
	}

	private record LangChainModel(NativeTurn turn) implements ChatModel {
		@Override
		public CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse> doChatAsync(
				dev.langchain4j.model.chat.request.ChatRequest request) {
			return turn.chunks(Flux.just(langChainResponse(turn, request))).single().toFuture();
		}
	}

	private record LangChainStream(NativeTurn turn) implements StreamingChatModel {
		@Override
		public Flow.Publisher<ChatModelStreamingEvent> doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
			var response = langChainResponse(turn, request);
			List<ChatModelStreamingEvent> events = new ArrayList<>();
			if (!response.aiMessage().hasToolExecutionRequests()) {
				events.add(new PartialResponse("do"));
				events.add(new PartialResponse("ne"));
			}
			events.add(new CompleteResponse(response));
			return JdkFlowAdapter.publisherToFlowPublisher(turn.chunks(Flux.fromIterable(events)));
		}
	}

	private static final class AdkModel extends BaseLlm {

		private final NativeTurn turn;

		private AdkModel(NativeTurn turn) {
			super("test-model");
			this.turn = turn;
		}

		@Override
		public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
			int index = turn.calls.getAndIncrement();
			assertTrue(request.tools().isEmpty());
			assertEquals("lookup",
					request.config()
						.orElseThrow()
						.tools()
						.orElseThrow()
						.get(0)
						.functionDeclarations()
						.orElseThrow()
						.get(0)
						.name()
						.orElseThrow());
			if (index > 0) {
				var result = request.contents().get(2).parts().orElseThrow().get(0).functionResponse().orElseThrow();
				assertEquals("lookup_1", result.id().orElseThrow());
				assertEquals("lookup", result.name().orElseThrow());
				assertEquals("found", result.response().orElseThrow().get("result"));
			}
			Part part = index == 0 ? Part.builder()
				.functionCall(FunctionCall.builder().id("lookup_1").name("lookup").args(Map.of()).build())
				.build() : Part.builder().text("done").build();
			LlmResponse response = LlmResponse.builder()
				.content(Content.builder().role("model").parts(part).build())
				.build();
			return Flowable.fromPublisher(turn.chunks(Flux.just(response)));
		}

		@Override
		public BaseLlmConnection connect(LlmRequest request) {
			throw new AssertionError("Chat turns must not open live connections");
		}

	}

	private record ScopeModel(NativeTurn turn) implements Model {
		@Override
		public Flux<io.agentscope.core.model.ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
				GenerateOptions options) {
			int index = turn.calls.getAndIncrement();
			assertEquals("lookup", tools.get(0).getName());
			if (index > 0) {
				var result = messages.get(2).getFirstContentBlock(ToolResultBlock.class);
				assertEquals("lookup_1", result.getId());
				assertEquals("lookup", result.getName());
				assertEquals("found", ((TextBlock) result.getOutput().get(0)).getText());
			}
			var block = index == 0 ? ToolUseBlock.builder().id("lookup_1").name("lookup").input(Map.of()).build()
					: TextBlock.builder().text("done").build();
			var response = io.agentscope.core.model.ChatResponse.builder()
				.content(List.of(block))
				.usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
				.build();
			return turn.chunks(Flux.just(response));
		}

		@Override
		public String getModelName() {
			return "test-model";
		}
	}

}
