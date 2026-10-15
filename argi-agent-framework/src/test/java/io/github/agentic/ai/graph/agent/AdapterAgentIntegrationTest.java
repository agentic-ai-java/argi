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
package io.github.agentic.ai.graph.agent;

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
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import io.github.agentic.ai.adapter.googleadk.GoogleAdkChatClientAdapter;
import io.github.agentic.ai.adapter.langchain4j.LangChain4jChatClientAdapter;
import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import io.github.agentic.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import io.github.agentic.ai.graph.agent.flow.agent.SequentialAgent;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises real adapter conversions with offline native models, not mocked ARGI clients.
 */
class AdapterAgentIntegrationTest {

	@TempDir
	Path checkpointFolder;

	private static final String INSTRUCTION = "Answer carefully.";

	enum Framework {

		SPRING_AI, LANGCHAIN4J, GOOGLE_ADK

	}

	static Stream<Arguments> frameworksAndModes() {
		return Stream.of(Framework.values())
			.flatMap(framework -> Stream.of(false, true).map(streaming -> Arguments.of(framework, streaming)));
	}

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("frameworksAndModes")
	void toolLoopPersistsPairedHistoryAndDoesNotDuplicateStreamText(Framework framework, boolean streaming)
			throws Exception {
		NativeTurn turn = new NativeTurn(true, "done");
		AtomicInteger executions = new AtomicInteger();
		MemorySaver saver = new MemorySaver();
		RunnableConfig config = RunnableConfig.builder().threadId("adapter-loop").build();
		ReactAgent agent = agent(framework, turn, saver, tool(executions, false));

		assertEquals("done", execute(agent, config, streaming));
		assertEquals(2, turn.calls.get());
		assertEquals(1, executions.get());
		assertTrue(turn.receivedToolResult);
		assertEquals(2, turn.instructions.size());
		turn.instructions.forEach(text -> assertEquals(INSTRUCTION, text));

		List<?> history = (List<?>) saver.get(config).orElseThrow().getState().get("messages");
		assertEquals(1, history.stream().filter(message -> ((ChatMessage) message).role() == ChatRole.TOOL).count());
		int resultIndex = -1;
		for (int index = 0; index < history.size(); index++) {
			if (history.get(index) instanceof ChatMessage result && result.role() == ChatRole.TOOL) {
				resultIndex = index;
				assertEquals("lookup_1", result.toolCallId());
				assertEquals("found", result.text());
			}
		}
		assertTrue(resultIndex > 0);
		ChatMessage invocation = (ChatMessage) history.get(resultIndex - 1);
		assertEquals("lookup_1", invocation.toolCalls().get(0).id());
		assertEquals("done", ((ChatMessage) history.get(history.size() - 1)).text());
	}

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("frameworksAndModes")
	void returnDirectEndsWithoutAnotherNativeModelTurn(Framework framework, boolean streaming) throws Exception {
		NativeTurn turn = new NativeTurn(true, "must not run");
		AtomicInteger executions = new AtomicInteger();
		ReactAgent agent = agent(framework, turn, new MemorySaver(), tool(executions, true));

		assertEquals("found", execute(agent, RunnableConfig.builder().threadId("direct").build(), streaming));
		assertEquals(1, turn.calls.get());
		assertEquals(1, executions.get());
	}

	@ParameterizedTest(name = "{0}, streaming={1}")
	@MethodSource("frameworksAndModes")
	void interruptedToolTurnResumesAfterAgentAndSaverRebuild(Framework framework, boolean streaming) throws Exception {
		NativeTurn turn = new NativeTurn(true, "done");
		AtomicInteger executions = new AtomicInteger();
		Path folder = checkpointFolder.resolve(framework + "_" + streaming);
		RunnableConfig config = RunnableConfig.builder().threadId("interrupted-loop").build();
		ToolRegistry registry = new ToolRegistry(List.of(tool(executions, false)));
		ReactAgent first = ReactAgent.builder()
			.name("adapter_agent")
			.chatClient(client(framework, turn))
			.instruction(INSTRUCTION)
			.toolRegistry(registry)
			.compileConfig(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(fileSaver(folder)).build())
				.interruptBefore(RunnableConfig.AGENT_TOOL_NAME)
				.build())
			.build();

		if (streaming) {
			first.streamMessages("question", config).collectList().block(Duration.ofSeconds(10));
		}
		else {
			first.invoke("question", config);
		}
		assertEquals(1, turn.calls.get());
		assertEquals(0, executions.get());
		var checkpoint = fileSaver(folder).get(config).orElseThrow();
		assertEquals(RunnableConfig.AGENT_TOOL_NAME, checkpoint.getNextNodeId());
		List<?> pending = (List<?>) checkpoint.getState().get("messages");
		assertEquals("lookup_1", ((ChatMessage) pending.get(pending.size() - 1)).toolCalls().get(0).id());

		ReactAgent rebuilt = ReactAgent.builder()
			.name("adapter_agent")
			.chatClient(client(framework, turn))
			.instruction(INSTRUCTION)
			.toolRegistry(registry)
			.saver(fileSaver(folder))
			.build();
		// A new RunnableConfig prevents process-local context from leaking into the
		// restored execution.
		RunnableConfig resumed = RunnableConfig.builder()
			.threadId("interrupted-loop")
			.checkPointId(checkpoint.getId())
			.build();
		rebuilt.getAndCompileGraph().stream(null, resumed).collectList().block(Duration.ofSeconds(10));

		assertEquals(2, turn.calls.get());
		assertEquals(1, executions.get());
		assertTrue(turn.receivedToolResult);
		List<?> history = (List<?>) fileSaver(folder).get(config).orElseThrow().getState().get("messages");
		assertEquals(1,
				history.stream()
					.filter(value -> value instanceof ChatMessage message
							&& message.role() == io.github.agentic.ai.api.chat.ChatRole.TOOL)
					.count());
		assertEquals("done", ((ChatMessage) history.get(history.size() - 1)).text());
	}

	private static FileSystemSaver fileSaver(Path folder) {
		return FileSystemSaver.builder()
			.targetFolder(folder)
			.stateSerializer(new ArgiJacksonStateSerializer(OverAllState::new))
			.build();
	}

	static Stream<Arguments> frameworksModesAndDecisions() {
		return Stream.of(Framework.values())
			.flatMap(framework -> Stream.of(false, true)
				.flatMap(streaming -> Stream.of(FeedbackResult.values())
					.map(decision -> Arguments.of(framework, streaming, decision))));
	}

	@ParameterizedTest(name = "{0}, streaming={1}, decision={2}")
	@MethodSource("frameworksModesAndDecisions")
	void humanApprovalResumesAfterRebuildWithoutExecutingRejectedTools(Framework framework, boolean streaming,
			FeedbackResult decision) throws Exception {
		NativeTurn turn = new NativeTurn(true, "done");
		turn.rejected = decision == FeedbackResult.REJECTED;
		AtomicInteger executions = new AtomicInteger();
		String arguments = decision == FeedbackResult.EDITED ? "{\"reviewed\":true}" : "{}";
		ToolRegistry registry = new ToolRegistry(List.of(tool(executions, false, arguments)));
		Path folder = checkpointFolder.resolve(framework + "_" + streaming + "_" + decision);
		RunnableConfig config = RunnableConfig.builder().threadId("human-approval").build();
		ReactAgent first = approvalAgent(framework, turn, registry, folder);
		if (streaming) {
			first.streamMessages("question", config).collectList().block(Duration.ofSeconds(10));
		}
		else {
			first.invoke("question", config);
		}
		assertEquals(1, turn.calls.get());
		assertEquals(0, executions.get());
		var checkpoint = fileSaver(folder).get(config).orElseThrow();
		var feedback = InterruptionMetadata.builder()
			.addToolFeedback(ToolFeedback.builder()
				.id("lookup_1")
				.name("lookup")
				.result(decision)
				.arguments(arguments)
				.description("Reviewed")
				.build())
			.build();
		var resumed = RunnableConfig.builder()
			.threadId("human-approval")
			.checkPointId(checkpoint.getId())
			.addHumanFeedback(feedback)
			.build();
		approvalAgent(framework, turn, registry, folder).getAndCompileGraph()
			.stream(null, resumed)
			.collectList()
			.block(Duration.ofSeconds(10));

		assertEquals(2, turn.calls.get());
		assertEquals(decision == FeedbackResult.REJECTED ? 0 : 1, executions.get());
		assertTrue(turn.receivedToolResult);
		List<?> history = (List<?>) fileSaver(folder).get(config).orElseThrow().getState().get("messages");
		assertEquals(1,
				history.stream()
					.filter(value -> value instanceof ChatMessage message
							&& message.role() == io.github.agentic.ai.api.chat.ChatRole.TOOL)
					.count());
		assertEquals("done", ((ChatMessage) history.get(history.size() - 1)).text());
	}

	private static ReactAgent approvalAgent(Framework framework, NativeTurn turn, ToolRegistry registry, Path folder) {
		return ReactAgent.builder()
			.name("adapter_agent")
			.chatClient(client(framework, turn))
			.instruction(INSTRUCTION)
			.toolRegistry(registry)
			.saver(fileSaver(folder))
			.hooks(HumanInTheLoopHook.builder().approvalOn("lookup", "Review lookup").build())
			.build();
	}

	@ParameterizedTest
	@EnumSource(Framework.class)
	void cancellingAgentDisposesNativeStreamAndCommitsNoPartialResponse(Framework framework) throws Exception {
		NativeTurn turn = new NativeTurn(true, "unused");
		turn.wait = true;
		AtomicInteger executions = new AtomicInteger();
		MemorySaver saver = new MemorySaver();
		RunnableConfig config = RunnableConfig.builder().threadId("cancelled").build();
		var subscription = agent(framework, turn, saver, tool(executions, false)).streamMessages("question", config)
			.subscribe();
		try {
			assertTrue(turn.started.await(5, TimeUnit.SECONDS));
		}
		finally {
			subscription.dispose();
		}
		assertTrue(turn.cancelled.await(5, TimeUnit.SECONDS));
		assertEquals(0, executions.get());
		saver.get(config).ifPresent(checkpoint -> {
			List<?> history = (List<?>) checkpoint.getState().getOrDefault("messages", List.of());
			assertFalse(history.stream().anyMatch(message -> ((ChatMessage) message).role() == ChatRole.ASSISTANT));
			assertFalse(history.stream().anyMatch(message -> ((ChatMessage) message).role() == ChatRole.TOOL));
		});
	}

	@ParameterizedTest
	@EnumSource(Framework.class)
	void sequentialFlowPassesPriorOutputIntoRenderedInstruction(Framework framework) throws Exception {
		NativeTurn writer = new NativeTurn(false, "draft {literal}");
		NativeTurn reviewer = new NativeTurn(false, "reviewed");
		ReactAgent first = ReactAgent.builder()
			.name("writer")
			.chatClient(client(framework, writer))
			.instruction("Write {input}")
			.outputKey("article")
			.build();
		ReactAgent second = ReactAgent.builder()
			.name("reviewer")
			.chatClient(client(framework, reviewer))
			.instruction("Review {article}")
			.outputKey("reviewed_article")
			.build();
		var result = SequentialAgent.builder()
			.name("editorial")
			.subAgents(List.of(first, second))
			.build()
			.invoke("question")
			.orElseThrow();

		assertEquals(1, writer.calls.get());
		assertEquals(1, reviewer.calls.get());
		assertEquals(List.of("Write question"), writer.instructions);
		assertEquals(List.of("Review draft {literal}"), reviewer.instructions);
		assertEquals("reviewed", ((ChatMessage) result.value("reviewed_article").orElseThrow()).text());
	}

	private static ReactAgent agent(Framework framework, NativeTurn turn, MemorySaver saver, Tool tool) {
		return ReactAgent.builder()
			.name("adapter_agent")
			.chatClient(client(framework, turn))
			.instruction(INSTRUCTION)
			.toolRegistry(new ToolRegistry(List.of(tool)))
			.saver(saver)
			.build();
	}

	private static String execute(ReactAgent agent, RunnableConfig config, boolean streaming) throws Exception {
		if (!streaming) {
			return agent.call("question", config).text();
		}
		List<ChatMessage> messages = agent.streamMessages("question", config)
			.collectList()
			.block(Duration.ofSeconds(10));
		assertNotNull(messages);
		assertEquals(1, messages.stream().filter(message -> message.role() == ChatRole.TOOL).count());
		return messages.stream()
			.filter(message -> message.role() == ChatRole.ASSISTANT && message.toolCalls().isEmpty())
			.map(ChatMessage::text)
			.filter(text -> text != null && !text.isEmpty())
			.reduce("", String::concat);
	}

	private static Tool tool(AtomicInteger executions, boolean direct) {
		return tool(executions, direct, "{}");
	}

	private static Tool tool(AtomicInteger executions, boolean direct, String expectedArguments) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "Lookup", "{\"type\":\"object\",\"properties\":{}}");
			}

			@Override
			public boolean returnDirect() {
				return direct;
			}

			@Override
			public CompletableFuture<ToolResult> execute(ToolCall call, ToolContext context) {
				assertEquals(expectedArguments, call.arguments());
				executions.incrementAndGet();
				return CompletableFuture.completedFuture(ToolResult.text("found"));
			}
		};
	}

	private static ChatClient client(Framework framework, NativeTurn turn) {
		return switch (framework) {
			case SPRING_AI -> new SpringAiChatClientAdapter(new SpringModel(turn));
			case LANGCHAIN4J -> new LangChain4jChatClientAdapter(new LangChainModel(turn), new LangChainStream(turn));
			case GOOGLE_ADK -> new GoogleAdkChatClientAdapter(new AdkModel(turn),
					new ChatClientCapabilities(true, true, true, false, true, true));
		};
	}

	private static final class NativeTurn {

		private final boolean tools;

		private final String answer;

		private final AtomicInteger calls = new AtomicInteger();

		private final List<String> instructions = new CopyOnWriteArrayList<>();

		private final CountDownLatch started = new CountDownLatch(1);

		private final CountDownLatch cancelled = new CountDownLatch(1);

		private volatile boolean receivedToolResult;

		private boolean wait;

		private boolean rejected;

		private NativeTurn(boolean tools, String answer) {
			this.tools = tools;
			this.answer = answer;
		}

		private boolean toolTurn() {
			return calls.getAndIncrement() == 0 && tools;
		}

		private <T> Flux<T> chunks(Flux<T> response) {
			return (wait ? Flux.<T>never() : response).doOnSubscribe(ignored -> started.countDown())
				.doOnCancel(cancelled::countDown);
		}

		private void result(String id, String name, String text) {
			assertEquals("lookup_1", id);
			assertEquals("lookup", name);
			if (rejected) {
				assertTrue(text.contains("rejected by human"));
			}
			else {
				assertEquals("found", text);
			}
			receivedToolResult = true;
		}

	}

	private record SpringModel(NativeTurn turn) implements org.springframework.ai.chat.model.ChatModel {
		private org.springframework.ai.chat.model.ChatResponse response(Prompt request) {
			request.getInstructions().forEach(message -> {
				if (message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
						&& !"question".equals(message.getText())) {
					turn.instructions.add(message.getText());
				}
				if (message instanceof ToolResponseMessage result) {
					var entry = result.getResponses().get(0);
					turn.result(entry.id(), entry.name(), entry.responseData());
				}
			});
			if (turn.tools) {
				var options = (ToolCallingChatOptions) request.getOptions();
				assertEquals("lookup", options.getToolCallbacks().get(0).getToolDefinition().name());
			}
			boolean tool = turn.toolTurn();
			AssistantMessage output = tool ? AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("lookup_1", "function", "lookup", "{}")))
				.build() : new AssistantMessage(turn.answer);
			return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(output)));
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
		request.messages().forEach(message -> {
			if (message instanceof dev.langchain4j.data.message.UserMessage user
					&& !"question".equals(user.singleText())) {
				turn.instructions.add(user.singleText());
			}
			if (message instanceof ToolExecutionResultMessage result) {
				turn.result(result.id(), result.toolName(), result.text());
			}
		});
		if (turn.tools) {
			assertEquals("lookup", request.toolSpecifications().get(0).name());
		}
		boolean tool = turn.toolTurn();
		return dev.langchain4j.model.chat.response.ChatResponse.builder()
			.aiMessage(tool
					? AiMessage
						.from(ToolExecutionRequest.builder().id("lookup_1").name("lookup").arguments("{}").build())
					: AiMessage.from(turn.answer))
			.build();
	}

	private record LangChainModel(NativeTurn turn) implements dev.langchain4j.model.chat.ChatModel {
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
				int middle = turn.answer.length() / 2;
				events.add(new PartialResponse(turn.answer.substring(0, middle)));
				events.add(new PartialResponse(turn.answer.substring(middle)));
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
			assertTrue(request.tools().isEmpty());
			request.contents().forEach(content -> content.parts().orElseThrow().forEach(part -> {
				part.text()
					.filter(text -> "user".equals(content.role().orElse("")) && !"question".equals(text))
					.ifPresent(turn.instructions::add);
				part.functionResponse()
					.ifPresent(result -> turn.result(result.id().orElseThrow(), result.name().orElseThrow(),
							(String) result.response().orElseThrow().get("result")));
			}));
			if (turn.tools) {
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
			}
			boolean tool = turn.toolTurn();
			Part part = tool ? Part.builder()
				.functionCall(FunctionCall.builder().id("lookup_1").name("lookup").args(Map.of()).build())
				.build() : Part.builder().text(turn.answer).build();
			return Flowable.fromPublisher(turn.chunks(Flux
				.just(LlmResponse.builder().content(Content.builder().role("model").parts(part).build()).build())));
		}

		@Override
		public BaseLlmConnection connect(LlmRequest request) {
			throw new AssertionError("Chat turns must not open live connections");
		}

	}

}
