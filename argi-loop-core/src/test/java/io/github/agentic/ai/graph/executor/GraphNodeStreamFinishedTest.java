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
import io.github.agentic.ai.graph.stream.ScriptedChatClient;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.stream.LLmNodeAction;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link io.github.agentic.ai.graph.executor.NodeExecutor} stream completion
 * behavior for graph LLM nodes: {@link OutputType#GRAPH_NODE_FINISHED} must not repeat
 * text already sent as {@link OutputType#GRAPH_NODE_STREAMING} deltas.
 */
public class GraphNodeStreamFinishedTest {

	private static final String EXPECTED_FULL_TEXT = "foo bar baz";

	@Test
	void testGraphNodeFinishedDoesNotRepeatFullTextOnStream() throws Exception {
		CompiledGraph compiledGraph = buildGraph(createIncrementalMockChatModel());
		Map<String, Object> input = Map.of(OverAllState.DEFAULT_INPUT_KEY, "test");

		StringBuilder appendAllNonEmptyChunks = new StringBuilder();
		AtomicReference<StreamingOutput<?>> finishedOutput = new AtomicReference<>();

		NodeOutput last = compiledGraph.stream(input).doOnNext(output -> {
			if (!(output instanceof StreamingOutput<?> so)) {
				return;
			}
			if (so.getOutputType() == OutputType.GRAPH_NODE_FINISHED) {
				finishedOutput.set(so);
			}
			appendChunkIfPresent(appendAllNonEmptyChunks, so);
		}).blockLast(Duration.ofSeconds(10));

		assertNotNull(finishedOutput.get(), "stream should emit GRAPH_NODE_FINISHED");
		assertTrue(isEmptyText(finishedOutput.get()), "GRAPH_NODE_FINISHED must not expose full text in chunk/message");

		assertEquals(EXPECTED_FULL_TEXT, appendAllNonEmptyChunks.toString(),
				"appending all non-empty stream chunks should equal full text once, not twice");
		assertEquals(EXPECTED_FULL_TEXT, extractLastAssistantText(last),
				"final state should still contain the full assistant message");
	}

	private static CompiledGraph buildGraph(ChatClient chatModel) throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("messages", new AppendStrategy());
			return strategies;
		}).addNode("streaming_node", node_async(new LLmNodeAction(chatModel, "streaming_node")))
			.addEdge(START, "streaming_node")
			.addEdge("streaming_node", END);
		return stateGraph.compile();
	}

	private static ChatClient createIncrementalMockChatModel() {
		return new ScriptedChatClient(() -> Flux.just(ChatEvent.textDelta("foo"), ChatEvent.textDelta(" bar"),
				ChatEvent.textDelta(" baz"), ChatEvent.response(new ChatResponse(null, null,
						List.of(ChatMessage.assistant(EXPECTED_FULL_TEXT)), List.of(), null, null, Map.of()))));
	}

	/** Append whenever visible assistant text is non-empty (naive stream consumer). */
	private static void appendChunkIfPresent(StringBuilder sink, StreamingOutput<?> so) {
		String chunk = chunkText(so);
		if (chunk != null && !chunk.isEmpty()) {
			sink.append(chunk);
		}
	}

	private static String chunkText(StreamingOutput<?> so) {
		return so.text();
	}

	private static boolean isEmptyText(StreamingOutput<?> so) {
		String chunk = chunkText(so);
		return chunk == null || chunk.isEmpty();
	}

	private static String extractLastAssistantText(NodeOutput last) {
		if (last == null || last.state() == null) {
			return "";
		}
		Object messages = last.state().value("messages").orElse(null);
		if (messages instanceof Iterable<?> list) {
			ChatMessage lastAssistant = null;
			for (Object item : list) {
				if (item instanceof ChatMessage am) {
					lastAssistant = am;
				}
			}
			return lastAssistant != null && lastAssistant.text() != null ? lastAssistant.text() : "";
		}
		return "";
	}

}
