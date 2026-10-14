/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.compatibility;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.exception.GraphRunnerException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAgentBehaviorCompatibilityTest {

	@Test
	void callInvokeAndStreamMessagesExposeLegacyAssistantResponseOnly() throws GraphRunnerException {
		ReactAgent agent = ReactAgent.builder()
			.name("legacy-agent")
			.chatClient(
					new io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter(new DeterministicChatModel()))
			.build();

		assertThat(agent.call("hello").text()).isEqualTo("legacy-response");

		Optional<OverAllState> invoked = agent.invoke("hello");
		assertThat(invoked).isPresent();
		assertThat(invoked.get().value("messages")).isPresent();

		List<String> streamedMessages = agent.streamMessages("hello")
			.map(io.github.agentic.ai.api.chat.ChatMessage::text)
			.collectList()
			.block(Duration.ofSeconds(2));
		assertThat(streamedMessages).containsExactly("legacy-response");
	}

	private static final class DeterministicChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("legacy-response"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

}
