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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class AgentNeutralApiTest {

	private final Agent agent = mock(Agent.class, CALLS_REAL_METHODS);

	@Test
	void actualAgentCallAndInvokeAcceptNeutralMultimodalHistory() throws Exception {
		for (String entry : List.of("message", "history", "map", "invoke")) {
			var received = new AtomicReference<ChatRequest>();
			var client = mock(ChatClient.class, CALLS_REAL_METHODS);
			when(client.capabilities()).thenReturn(new ChatClientCapabilities(false, false, false, true, true, true));
			var expected = new ChatMessage(ChatRole.ASSISTANT, List.of(ContentPart.json("{\"ok\":true}")),
					Map.of("source", "model"));
			when(client.call(any())).thenAnswer(invocation -> {
				received.set(invocation.getArgument(0));
				return CompletableFuture.completedFuture(new ChatResponse("id", "mock", List.of(expected), List.of(),
						FinishReason.STOP, null, Map.of()));
			});
			var runtime = ReactAgent.builder().name("neutral-input").chatClient(client).build();
			var input = new ChatMessage(ChatRole.USER,
					List.of(ContentPart.text("question"),
							ContentPart.binary(ContentPart.Type.IMAGE, "image/png", new byte[] { 1, 2 })),
					Map.of("source", "caller"));
			var history = List.of(ChatMessage.system("instructions"), ChatMessage.user("first"),
					ChatMessage.assistant("earlier"), input);
			var state = Map.<String, Object>of("messages", history, "marker", "caller");
			ChatMessage answer = switch (entry) {
				case "message" -> runtime.call(input);
				case "history" -> runtime.call(history);
				case "map" -> runtime.call(state);
				default -> {
					var output = runtime.invoke(state).orElseThrow();
					@SuppressWarnings("unchecked")
					var messages = (List<ChatMessage>) output.value("messages").orElseThrow();
					yield messages.get(messages.size() - 1);
				}
			};
			assertThat(answer).isEqualTo(expected);
			var request = received.get();
			assertThat(request).isNotNull();
			assertThat(request.messages()).hasSize(entry.equals("message") ? 1 : history.size());
			var last = request.messages().get(request.messages().size() - 1);
			assertThat(last.metadata()).isEqualTo(input.metadata());
			assertThat(last.content().get(1).data()).containsExactly(1, 2);
			assertThat(last.text()).isEqualTo("question");
			assertThat(state.get("messages")).isSameAs(history);
		}
	}

	@Test
	void selectsTheLastUserInputAndRetainsTheEntireConversation() {
		var messages = List.of(ChatMessage.system("system"), ChatMessage.user("first"), ChatMessage.assistant("answer"),
				ChatMessage.user("last"), ChatMessage.tool("id", "lookup", "result"));
		var input = agent.buildMessageInput(messages);
		assertThat(input).containsEntry("input", "last");
		assertThat(restored(input)).isEqualTo(messages);
		assertThat(agent.buildMessageInput(ChatMessage.assistant("answer"))).doesNotContainKey("input");
		assertThat(agent.buildMessageInput("question")).containsEntry("input", "question");
	}

	@Test
	void emptyStringResumesWithoutAddingABlankUserMessage() {
		assertThat(restored(agent.buildMessageInput(""))).isEmpty();
		assertThat(agent.buildMessageInput("")).doesNotContainKey("input");
	}

	@Test
	void mapNormalizationCopiesCallerStateInsteadOfMutatingIt() {
		var messages = List.of(ChatMessage.user("question"));
		var input = Map.<String, Object>of("messages", messages, "input", "explicit", "marker", "caller");
		var normalized = agent.normalizeMessageInput(input);
		assertThat(normalized).isNotSameAs(input).containsEntry("input", "explicit").containsEntry("marker", "caller");
		assertThat(input.get("messages")).isSameAs(messages);
		assertThat(restored(normalized)).isEqualTo(messages);
	}

	@Test
	void rejectsNullNativeAndMixedMessageValues() {
		assertThatThrownBy(() -> agent.buildMessageInput(null)).isInstanceOf(NullPointerException.class);
		for (Object value : List.of(new UserMessage("native"), List.of(new UserMessage("native")),
				List.of(ChatMessage.user("neutral"), new UserMessage("native")), 42)) {
			assertThatThrownBy(() -> agent.normalizeMessageInput(Map.of("messages", value)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ARGI ChatMessage");
		}
		var input = new HashMap<String, Object>();
		input.put("messages", null);
		assertThatThrownBy(() -> agent.normalizeMessageInput(input)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void publicConversationOverloadsContainNoNativeMessageTypes() {
		for (Class<?> type : List.of(Agent.class, ReactAgent.class)) {
			for (var method : type.getDeclaredMethods()) {
				if (Modifier.isPublic(method.getModifiers())
						&& List.of("call", "invoke", "invokeAndGetOutput", "stream", "streamMessages", "interrupt")
							.contains(method.getName())) {
					assertThat(method.toGenericString()).doesNotContain("org.springframework.ai");
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> restored(Map<String, Object> input) {
		return (List<ChatMessage>) input.get("messages");
	}

}
