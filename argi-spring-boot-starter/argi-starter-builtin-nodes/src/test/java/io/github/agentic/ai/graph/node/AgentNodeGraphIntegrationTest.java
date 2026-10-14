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
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentNodeGraphIntegrationTest {

	@TempDir
	Path checkpointFolder;

	private static Map<String, KeyStrategy> strategies() {
		return Map.of("messages", new AppendStrategy(), "agent_output", new ReplaceStrategy(), LlmNode.LLM_RESPONSE_KEY,
				new ReplaceStrategy(), "observed", new ReplaceStrategy());
	}

	private static Map<String, Object> input() {
		return Map.of("messages", List.of(ChatMessage.user("question")));
	}

	private static StateGraph definition(AgentNode node, AtomicInteger successors) throws Exception {
		return new StateGraph(AgentNodeGraphIntegrationTest::strategies).addNode("agent", node)
			.addNode("next", node_async(state -> {
				successors.incrementAndGet();
				assertTrue(state.value(NodeOutput.TOKEN_USAGE_KEY).isEmpty());
				return Map.of("observed", state.value("agent_output").orElseThrow());
			}))
			.addEdge(START, "agent")
			.addEdge("agent", "next")
			.addEdge("next", END);
	}

	@Test
	void commitsWholeLoopAndReportsAggregatedUsageOnlyOnAgentOutput() throws Exception {
		ChatClient client = mock(ChatClient.class);
		Tool tool = AgentNodeTest.tool("lookup");
		ToolCall call = new ToolCall("lookup_1", "lookup", "{}");
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(AgentNodeTest.calls(call)),
				CompletableFuture.completedFuture(AgentNodeTest.answer("done")));
		AgentNode node = AgentNode.builder().chatClient(client).toolRegistry(new ToolRegistry(List.of(tool))).build();
		AtomicInteger successors = new AtomicInteger();
		FileSystemSaver saver = FileSystemSaver.builder().targetFolder(checkpointFolder).build();
		CompiledGraph graph = definition(node, successors)
			.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
		RunnableConfig config = RunnableConfig.builder().threadId("whole-loop").build();
		List<NodeOutput> outputs = graph.stream(input(), config).collectList().block(Duration.ofSeconds(5));
		NodeOutput agentOutput = outputs.stream()
			.filter(output -> "agent".equals(output.node()))
			.findFirst()
			.orElseThrow();
		assertEquals(new Usage(6L, 3L, 9L), agentOutput.tokenUsage());
		assertEquals(1, successors.get());
		assertTrue(outputs.stream()
			.filter(output -> "next".equals(output.node()))
			.allMatch(output -> Usage.unknown().equals(output.tokenUsage())));
		assertTrue(outputs.stream().allMatch(output -> output.state().value(NodeOutput.TOKEN_USAGE_KEY).isEmpty()));

		// Reading with a fresh saver verifies persisted neutral records, not the
		// in-memory cache.
		OverAllState state = new OverAllState(
				FileSystemSaver.builder().targetFolder(checkpointFolder).build().get(config).orElseThrow().getState());
		assertEquals("done", state.value("observed").orElseThrow());
		List<?> messages = state.value("messages", List.class).orElseThrow();
		assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
				messages.stream().map(ChatMessage.class::cast).map(ChatMessage::role).toList());
		assertEquals("lookup_1", ((ChatMessage) messages.get(2)).toolCallId());
		assertEquals("done", state.value(LlmNode.LLM_RESPONSE_KEY, ChatResponse.class).orElseThrow().text());
		assertTrue(state.value(NodeOutput.TOKEN_USAGE_KEY).isEmpty());
		verify(tool).execute(any(), any());
	}

	@Test
	void failureKeepsCheckpointBeforeAgentAndDoesNotRunSuccessor() throws Exception {
		ChatClient client = mock(ChatClient.class);
		Tool tool = AgentNodeTest.tool("lookup");
		when(client.call(any())).thenReturn(
				CompletableFuture.completedFuture(AgentNodeTest.calls(new ToolCall("lookup_1", "lookup", "{}"))),
				CompletableFuture.failedFuture(new IllegalStateException("second turn failed")));
		AgentNode node = AgentNode.builder().chatClient(client).toolRegistry(new ToolRegistry(List.of(tool))).build();
		AtomicInteger successors = new AtomicInteger();
		FileSystemSaver saver = FileSystemSaver.builder().targetFolder(checkpointFolder).build();
		CompiledGraph graph = definition(node, successors)
			.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
		RunnableConfig config = RunnableConfig.builder().threadId("failed-loop").build();
		List<GraphResponse<NodeOutput>> outputs = graph.graphResponseStream(input(), config)
			.collectList()
			.block(Duration.ofSeconds(5));
		assertTrue(outputs.stream().anyMatch(GraphResponse::isError));
		assertEquals(0, successors.get());
		Map<String, Object> persisted = FileSystemSaver.builder()
			.targetFolder(checkpointFolder)
			.build()
			.get(config)
			.orElseThrow()
			.getState();
		assertEquals(input().get("messages"), persisted.get("messages"));
		assertTrue(!persisted.containsKey("agent_output") && !persisted.containsKey(LlmNode.LLM_RESPONSE_KEY));
		verify(tool).execute(any(), any());
	}

	@Test
	void replayingFailedNodeCanRepeatToolEffects() throws Exception {
		ChatClient client = mock(ChatClient.class);
		Tool tool = AgentNodeTest.tool("lookup");
		ChatResponse calls = AgentNodeTest.calls(new ToolCall("lookup_1", "lookup", "{}"));
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(calls),
				CompletableFuture.failedFuture(new IllegalStateException("second turn failed")),
				CompletableFuture.completedFuture(calls),
				CompletableFuture.completedFuture(AgentNodeTest.answer("replayed")));
		AgentNode node = AgentNode.builder().chatClient(client).toolRegistry(new ToolRegistry(List.of(tool))).build();
		AtomicInteger successors = new AtomicInteger();
		StateGraph definition = definition(node, successors);
		FileSystemSaver saver = FileSystemSaver.builder().targetFolder(checkpointFolder).build();
		CompiledGraph initial = definition
			.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build()).build());
		RunnableConfig config = RunnableConfig.builder().threadId("replayed-loop").build();
		assertTrue(initial.graphResponseStream(input(), config)
			.collectList()
			.block(Duration.ofSeconds(5))
			.stream()
			.anyMatch(GraphResponse::isError));

		FileSystemSaver restartedSaver = FileSystemSaver.builder().targetFolder(checkpointFolder).build();
		CompiledGraph restarted = definition.compile(
				CompileConfig.builder().saverConfig(SaverConfig.builder().register(restartedSaver).build()).build());
		OverAllState resumed = restarted
			.invoke(Map.of(), RunnableConfig.builder().threadId("replayed-loop").resume().build())
			.orElseThrow();
		assertEquals("replayed", resumed.value("observed").orElseThrow());
		assertEquals(4, ((List<?>) resumed.value("messages").orElseThrow()).size());
		assertEquals(1, successors.get());
		// Checkpoints are node-level; the first tool effect cannot be undone or skipped
		// on replay.
		verify(tool, times(2)).execute(any(), any());
		verify(client, times(4)).call(any());
	}

}
