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

import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Streaming-path regression tests for {@code returnDirect} tool semantics (issue #43).
 *
 * <p>
 * When a tool declares {@code returnDirect = true}, the tool response should be returned
 * to the caller directly and the model must not be invoked a second time. These tests pin
 * the behavior for the {@code stream()} / {@code streamMessages()} entry points,
 * mirroring the synchronous coverage in {@link AgentToolCallingAdvisorBoundaryTest}.
 * </p>
 */
class ReactAgentStreamingReturnDirectTest {

	static class DirectTools {

		final AtomicInteger executions = new AtomicInteger();

		@Tool(name = "direct_tool", description = "Returns directly", returnDirect = true)
		public String directTool() {
			executions.incrementAndGet();
			return "direct tool response";
		}

	}

	private static final class DirectToolCallThenFinalChatModel implements ChatModel {

		private final AtomicInteger callCount = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			if (callCount.incrementAndGet() == 1) {
				AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "direct_tool",
						"{}");
				return new ChatResponse(List
					.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("final response"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	@Test
	void streamMessagesReturnDirectShouldEndWithoutSecondModelCall() throws Exception {
		DirectToolCallThenFinalChatModel chatModel = new DirectToolCallThenFinalChatModel();
		DirectTools tools = new DirectTools();

		ReactAgent agent = ReactAgent.builder()
			.name("streaming-direct-tool-agent")
			.chatClient(new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(chatModel))
			.toolRegistry(io.github.agentic.ai.adapter.springai.SpringAiToolAdapter
				.fromCallbacks(ToolCallbacks.from(tools)[0]))
			.saver(new MemorySaver())
			.build();

		List<io.github.agentic.ai.api.chat.ChatMessage> messages = agent.streamMessages("call the direct tool")
			.collectList()
			.block();

		assertThat(messages).isNotNull();
		assertThat(messages).isNotEmpty();
		String lastText = messages.get(messages.size() - 1).text();
		assertThat(lastText).contains("direct tool response").doesNotContain("final response");
		assertThat(chatModel.callCount.get()).isEqualTo(1);
		assertThat(tools.executions.get()).isEqualTo(1);
	}

}
