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
package io.github.agentic.ai.graph.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.messages.AssistantMessage;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for <a href="https://github.com/agentic-ai-java/argi/issues/25">issue
 * #25</a>: when a streaming run is cancelled mid-flight (client timeout, SSE disconnect),
 * the persisted checkpoint may contain a {@link ToolResponseMessage} whose preceding
 * assistant tool_call never made it into the checkpoint. The next turn then loads an
 * invalid message sequence and the LLM rejects the request with 400.
 */
class Issue25StreamCancelCheckpointTest {

	private static final class LookupTools {

		@Tool(description = "look up something")
		public String lookup(@ToolParam(description = "the query") String query) {
			return "result-for-" + query;
		}

	}

	private static final class ToolCallThenFinalChatModel implements ChatModel {

		private final AtomicInteger callCount = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			if (callCount.incrementAndGet() == 1) {
				AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "lookup",
						"{\"query\": \"x\"}");
				return new ChatResponse(List
					.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("final answer"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	private ReactAgent agentWith(MemorySaver saver, String name) {
		ToolCallback lookup = ToolCallbacks.from(new LookupTools())[0];
		return ReactAgent.builder()
			.name(name)
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(
					new ToolCallThenFinalChatModel()))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter.fromCallbacks(lookup))
			.saver(saver)
			.build();
	}

	private Checkpoint checkpointOf(MemorySaver saver, RunnableConfig config) {
		return saver.get(config).orElse(null);
	}

	@SuppressWarnings("unchecked")
	private List<ChatMessage> messagesOf(Checkpoint checkpoint) {
		Object messages = checkpoint.getState().get("messages");
		return messages instanceof List<?> list ? (List<ChatMessage>) list : List.of();
	}

	private void assertToolCallPairsValid(List<ChatMessage> messages) {
		ChatMessage previous = null;
		int toolCalls = 0;
		int toolResponses = 0;
		StringBuilder description = new StringBuilder();
		for (ChatMessage message : messages) {
			if (description.length() > 0) {
				description.append(" -> ");
			}
			description.append(message.role());
			if (message.role() == ChatRole.ASSISTANT && message.hasToolCalls()) {
				description.append("(tool_call)");
			}
			if (message.role() == ChatRole.TOOL) {
				toolResponses++;
				description.append("(tool)");
				assertTrue(previous != null && previous.role() == ChatRole.ASSISTANT && previous.hasToolCalls(),
						"persisted sequence invalid: tool response preceded by " + previous + " in " + description);
			}
			if (message.role() == ChatRole.ASSISTANT && message.hasToolCalls()) {
				toolCalls++;
			}
			previous = message;
		}
		if (toolCalls != toolResponses) {
			throw new AssertionError("assistant tool_call and tool response must be paired; expected " + toolCalls
					+ " responses but was " + toolResponses + " in " + description);
		}
	}

	private void assertToolCallPairsValid(Checkpoint checkpoint) {
		assertToolCallPairsValid(messagesOf(checkpoint));
	}

	@Test
	void fullStreamingRunPersistsValidSequence() throws Exception {
		MemorySaver saver = new MemorySaver();
		ReactAgent agent = agentWith(saver, "issue25_full_agent");
		RunnableConfig config = RunnableConfig.builder().threadId("issue25-full").build();

		List<io.github.agentic.ai.api.chat.ChatMessage> seen = agent
			.streamMessages(io.github.agentic.ai.api.chat.ChatMessage.user("please look up x"), config)
			.collectList()
			.block(Duration.ofSeconds(10));

		assertNotNull(seen);
		Checkpoint checkpoint = checkpointOf(saver, config);
		assertNotNull(checkpoint, "a checkpoint must exist after a completed run");
		assertToolCallPairsValid(checkpoint);
	}

	@Test
	void cancelledStreamMustNotLeaveOrphanToolResponse() throws Exception {
		MemorySaver saver = new MemorySaver();
		ReactAgent agent = agentWith(saver, "issue25_cancel_agent");
		RunnableConfig config = RunnableConfig.builder().threadId("issue25-cancel").build();

		CountDownLatch firstEvent = new CountDownLatch(1);
		Disposable disposable = agent
			.streamMessages(io.github.agentic.ai.api.chat.ChatMessage.user("please look up x"), config)
			.doOnNext(message -> firstEvent.countDown())
			.subscribe();
		assertTrue(firstEvent.await(5, TimeUnit.SECONDS), "the stream should emit at least one event");
		disposable.dispose();
		// Give any background persistence (the buggy path) time to land before
		// inspecting.
		Thread.sleep(2000);

		Checkpoint checkpoint = checkpointOf(saver, config);
		if (checkpoint == null) {
			return; // nothing persisted at all: the invariant trivially holds
		}
		assertToolCallPairsValid(checkpoint);
	}

	@Test
	void disposingAfterCompletionKeepsTheFullTurn() throws Exception {
		MemorySaver saver = new MemorySaver();
		ReactAgent agent = agentWith(saver, "issue25_completed_agent");
		RunnableConfig config = RunnableConfig.builder().threadId("issue25-completed").build();

		CountDownLatch terminated = new CountDownLatch(1);
		Disposable disposable = agent
			.streamMessages(io.github.agentic.ai.api.chat.ChatMessage.user("please look up x"), config)
			.doFinally(signal -> terminated.countDown())
			.subscribe();
		assertTrue(terminated.await(10, TimeUnit.SECONDS), "the run should terminate");
		disposable.dispose(); // no-op on a completed stream: no cancel signal, no rewind

		Thread.sleep(200);
		Checkpoint checkpoint = checkpointOf(saver, config);
		assertNotNull(checkpoint);
		assertToolCallPairsValid(checkpoint);
		assertEquals(4, messagesOf(checkpoint).size(), "the completed turn must be fully recorded");
	}

	private static String describe(List<ChatMessage> messages) {
		StringBuilder sb = new StringBuilder();
		for (ChatMessage m : messages) {
			if (sb.length() > 0) {
				sb.append(" -> ");
			}
			sb.append(m.role());
			if (m.role() == ChatRole.ASSISTANT && m.hasToolCalls()) {
				sb.append("(tool_call)");
			}
			if (m.role() == ChatRole.TOOL) {
				sb.append("(tool)");
			}
		}
		return sb.toString();
	}

}
