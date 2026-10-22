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
package io.github.agentic.ai.graph.agent.flow;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.MessageProvenance;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.flow.agent.FlowAgent;
import io.github.agentic.ai.graph.agent.flow.agent.LoopAgent;
import io.github.agentic.ai.graph.agent.flow.agent.LlmRoutingAgent;
import io.github.agentic.ai.graph.agent.flow.agent.ParallelAgent;
import io.github.agentic.ai.graph.agent.flow.agent.SequentialAgent;
import io.github.agentic.ai.graph.agent.flow.agent.loop.LoopMode;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that repeated conversation turns survive flow execution and checkpoint reload. */
class FlowAgentMessageHistoryTest {

	@TempDir
	Path checkpointDirectory;

	@ParameterizedTest(name = "{0}, includeContents={1}, returnReasoningContents={2}")
	@MethodSource("flowConfigurations")
	void repeatedTurnsKeepNewMessagesWithoutReplayingHistory(String kind, boolean includeContents,
			boolean returnReasoningContents) throws Exception {
		RecordingClient client = new RecordingClient();
		FlowAgent agent = flow(kind, new MemorySaver(), client, includeContents, returnReasoningContents);
		RunnableConfig config = RunnableConfig.builder().threadId("repeated-turns").build();

		OverAllState first = agent.invoke(ChatMessage.user("continue"), config).orElseThrow();
		assertHistory(first, 1, 2);
		OverAllState second = agent.invoke(ChatMessage.user("continue"), config).orElseThrow();
		assertHistory(second, 2, 4);
		assertThat(client.requests).hasSize(4);
		if (includeContents) {
			assertThat(client.requests.get(2).messages().stream()
				.filter(message -> message.role() == ChatRole.USER).map(ChatMessage::text))
				.containsExactly("continue", "continue");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "sequential", "parallel", "nested", "loop" })
	void repeatedTurnSurvivesFileCheckpointAndAgentRecreation(String kind) throws Exception {
		RecordingClient client = new RecordingClient();
		RunnableConfig config = RunnableConfig.builder().threadId("file-turns").build();
		FlowAgent firstAgent = flow(kind, fileSaver(), client, true, true);
		assertHistory(firstAgent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 1, 2);

		FlowAgent reloadedAgent = flow(kind, fileSaver(), client, true, true);
		assertHistory(reloadedAgent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 2, 4);
		assertThat(client.requests.get(2).messages().stream()
			.filter(message -> message.role() == ChatRole.USER).map(ChatMessage::text))
			.containsExactly("continue", "continue");
	}

	@Test
	void repeatedInputStartsAnotherTurnForASingleChild() throws Exception {
		RecordingClient client = new RecordingClient();
		FlowAgent agent = SequentialAgent.builder().name("flow").saver(new MemorySaver())
			.subAgents(List.of(child("child", client, true, true))).build();
		RunnableConfig config = RunnableConfig.builder().threadId("single-child").build();
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 1, 1);
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 2, 2);
		assertThat(client.requests.get(1).messages().get(2).text()).isEqualTo("continue");
		assertThat(client.requests.get(1).messages().get(2).role()).isEqualTo(ChatRole.USER);
	}

	@Test
	void newChildMessageEqualToInheritedMessageIsStillAppended() throws Exception {
		RecordingClient client = new RecordingClient();
		client.response = MessageProvenance.forChildHandoff(ChatMessage.assistant("ack"));
		FlowAgent agent = flow("sequential", new MemorySaver(), client, true, true);
		RunnableConfig config = RunnableConfig.builder().threadId("equal-child-output").build();
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 1, 2);
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 2, 4);
	}

	@Test
	void sharedChildCheckpointDoesNotAppendTheParentSnapshotAgain() throws Exception {
		RecordingClient client = new RecordingClient();
		MemorySaver saver = new MemorySaver();
		ReactAgent child = ReactAgent.builder().name("child").chatClient(client).saver(saver)
			.includeContents(true).returnReasoningContents(true).build();
		FlowAgent agent = SequentialAgent.builder().name("flow").saver(saver)
			.subAgents(List.of(child)).build();
		RunnableConfig config = RunnableConfig.builder().threadId("shared-child").build();
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 1, 1);
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 2, 2);
		assertThat(client.requests.get(1).messages()).hasSize(3);
	}

	@Test
	void routingKeepsIdenticalInputsAcrossTurns() throws Exception {
		RecordingClient router = new RecordingClient();
		router.response = ChatMessage.assistant("{\"agents\":[{\"agent\":\"child\",\"query\":\"continue\"}]}");
		RecordingClient client = new RecordingClient();
		FlowAgent agent = LlmRoutingAgent.builder().name("flow").saver(new MemorySaver()).chatClient(router)
			.subAgents(List.of(child("child", client, true, true))).build();
		RunnableConfig config = RunnableConfig.builder().threadId("routing-turns").build();
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 1, 1);
		assertHistory(agent.invoke(ChatMessage.user("continue"), config).orElseThrow(), 2, 2);
		assertThat(router.requests).hasSize(2);
		assertThat(router.requests.get(1).messages().stream()
			.filter(message -> message.role() == ChatRole.USER).map(ChatMessage::text).filter("continue"::equals))
			.containsExactly("continue", "continue");
	}

	private FileSystemSaver fileSaver() {
		return FileSystemSaver.builder().targetFolder(checkpointDirectory).maxCachedThreads(0).build();
	}

	private static Stream<Arguments> flowConfigurations() {
		return Stream.of("sequential", "parallel", "nested", "loop").flatMap(kind ->
			Stream.of(true, false).flatMap(includeContents -> Stream.of(true, false)
				.map(reasoning -> Arguments.of(kind, includeContents, reasoning))));
	}

	private static FlowAgent flow(String kind, BaseCheckpointSaver saver, RecordingClient client,
			boolean includeContents, boolean returnReasoningContents) {
		ReactAgent first = child("first", client, includeContents, returnReasoningContents);
		ReactAgent second = child("second", client, includeContents, returnReasoningContents);
		return switch (kind) {
			case "sequential" -> SequentialAgent.builder().name("flow").saver(saver)
				.subAgents(List.of(first, second)).build();
			case "parallel" -> ParallelAgent.builder().name("flow").saver(saver)
				.subAgents(List.of(first, second)).build();
			case "nested" -> SequentialAgent.builder().name("flow").saver(saver)
				.subAgents(List.of(SequentialAgent.builder().name("inner")
					.subAgents(List.of(first, second)).build())).build();
			case "loop" -> LoopAgent.builder().name("flow").saver(saver).subAgent(first)
				.loopStrategy(LoopMode.count(2)).build();
			default -> throw new IllegalArgumentException("Unknown flow kind: " + kind);
		};
	}

	private static ReactAgent child(String name, RecordingClient client, boolean includeContents,
			boolean returnReasoningContents) {
		return ReactAgent.builder().name(name).chatClient(client).outputKey(name + "Output")
			.includeContents(includeContents).returnReasoningContents(returnReasoningContents).build();
	}

	private static void assertHistory(OverAllState state, int users, int assistants) {
		List<?> messages = state.<List<?>>value("messages").orElseThrow();
		assertThat(messages).hasSize(users + assistants);
		assertThat(messages.stream().filter(ChatMessage.class::isInstance).map(ChatMessage.class::cast)
			.filter(message -> message.role() == ChatRole.USER).map(ChatMessage::text))
			.hasSize(users).allMatch("continue"::equals);
		assertThat(messages.stream().filter(ChatMessage.class::isInstance).map(ChatMessage.class::cast)
			.filter(message -> message.role() == ChatRole.ASSISTANT).map(ChatMessage::text))
			.hasSize(assistants).allMatch("ack"::equals);
		assertThat(state.<String>value("input")).contains("continue");
	}

	private static final class RecordingClient implements ChatClient {

		private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

		private ChatMessage response = ChatMessage.assistant("ack");

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			requests.add(request);
			return CompletableFuture.completedFuture(new ChatResponse(null, null,
				List.of(response), List.of(), null, Usage.unknown(), Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException("Streaming is unavailable");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
