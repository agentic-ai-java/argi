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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.graph.GraphRunnerContext;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the reasoning-phase marker for streamed model chunks (issue #34): during
 * {@code AGENT_MODEL_STREAMING}, thinking chunks (blank content carrying
 * reasoningContent) are stamped {@code isReasoning=true} and answer chunks
 * {@code isReasoning=false}, so consumers can route the two phases without guessing.
 */
class Issue34ReasoningMarkerTest {

	private static final class ReasoningThenAnswerChatModel implements ChatModel {

		private final AtomicInteger callCount = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("final answer"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			if (callCount.incrementAndGet() == 1) {
				return Flux.just(
						new ChatResponse(List.of(new Generation(AssistantMessage.builder()
							.content("")
							.properties(Map.of("reasoningContent", "thinking hard about the query"))
							.build()))),
						new ChatResponse(List.of(new Generation(new AssistantMessage("final answer")))));
			}
			return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
		}

	}

	@Test
	void reasoningAndAnswerChunksAreStampedExplicitly() throws Exception {
		ReactAgent agent = ReactAgent.builder()
			.name("issue34_agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(
					new ReasoningThenAnswerChatModel()))
			.build();

		List<NodeOutput> outputs = agent.stream("please answer", RunnableConfig.builder().threadId("issue34").build())
			.collectList()
			.block(Duration.ofSeconds(10));

		assertNotNull(outputs);
		assertTrue(outputs.size() >= 2, "the model stream should emit at least a reasoning and an answer chunk");

		boolean sawReasoningChunk = false;
		boolean sawAnswerChunk = false;
		boolean lastChunkWasAnswer = false;
		int agentModelChunks = 0;

		for (NodeOutput output : outputs) {
			if (!(output instanceof StreamingOutput<?> chunk)
					|| chunk.getOutputType() != OutputType.AGENT_MODEL_STREAMING) {
				continue;
			}
			agentModelChunks++;
			var message = chunk.messages().get(0);
			assertNotNull(message);
			Object marker = message.metadata().get(GraphRunnerContext.IS_REASONING_METADATA_KEY);
			assertInstanceOf(Boolean.class, marker,
					"every AGENT_MODEL_STREAMING chunk must carry an explicit isReasoning marker, got metadata="
							+ message.metadata());
			if (Boolean.TRUE.equals(marker)) {
				sawReasoningChunk = true;
				assertEquals("", message.text(), "a reasoning chunk carries no visible answer text");
				assertEquals("thinking hard about the query",
						message.metadata().get(GraphRunnerContext.REASONING_CONTENT_METADATA_KEY),
						"the reasoning text must be preserved as metadata");
			}
			else {
				sawAnswerChunk = true;
			}
			lastChunkWasAnswer = Boolean.FALSE.equals(marker);
		}

		assertTrue(agentModelChunks >= 2, "expected at least two AGENT_MODEL_STREAMING chunks");
		assertTrue(sawReasoningChunk, "the reasoning chunk must be stamped isReasoning=true");
		assertTrue(sawAnswerChunk, "the answer chunk must be stamped isReasoning=false");
		assertTrue(lastChunkWasAnswer, "the last chunk must belong to the answer phase");
	}

}
