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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResponseTest {

	@Test
	void synchronousResponseRetainsPayloadAndRejectsStreamAccess() {
		ChatMessage message = ChatMessage.assistant("answer", List.of(new ToolCall("id", "lookup", "{}")));
		ModelResponse response = ModelResponse.of(message);
		assertThat(response.isStreaming()).isFalse();
		assertThat(response.getMessage()).isSameAs(message);
		assertThat(response.getChatResponse().toolCalls()).isEqualTo(message.toolCalls());
		assertThatThrownBy(response::getStream).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void streamingResponseRetainsPublisherAndRejectsSynchronousAccess() {
		Flow.Publisher<ChatEvent> publisher = JdkFlowAdapter.publisherToFlowPublisher(Flux.empty());
		ModelResponse response = ModelResponse.stream(publisher);
		assertThat(response.isStreaming()).isTrue();
		assertThat(response.getStream()).isSameAs(publisher);
		assertThatThrownBy(response::getMessage).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(response::getChatResponse).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsNullOrNonAssistantModelTurns() {
		assertThatThrownBy(() -> ModelResponse.of((ChatMessage) null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ModelResponse.of((ChatResponse) null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ModelResponse.stream(null)).isInstanceOf(NullPointerException.class);
		for (List<ChatMessage> messages : List.of(List.<ChatMessage>of(), List.of(ChatMessage.user("invalid")),
				List.of(ChatMessage.assistant("one"), ChatMessage.assistant("two")))) {
			assertThatThrownBy(
					() -> ModelResponse.of(new ChatResponse(null, null, messages, null, null, null, Map.of())))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

}
