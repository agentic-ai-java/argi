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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolSelectionContractTest {

	@Test
	void mandatoryToolSurvivesTheLimit() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"selected_tool\"]}", 1, "weather_tool");
		assertEquals(List.of("weather_tool"),
				select(interceptor, List.of("selected_tool", "weather_tool", "other_tool")));
	}

	@Test
	void mandatoryToolsReserveSlotsBeforeRankedTools() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"a\",\"b\"]}", 2, "c");
		assertEquals(List.of("a", "c"), select(interceptor, List.of("a", "b", "c", "d")));
	}

	@Test
	void mandatoryToolsTakePrecedenceWhenTheyExceedTheLimit() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"selected_tool\"]}", 1, "weather_tool",
				"clock_tool");
		assertEquals(List.of("weather_tool", "clock_tool"),
				select(interceptor, List.of("selected_tool", "weather_tool", "clock_tool")));
	}

	@Test
	void selectionRankDeterminesWhichToolsFitTheLimit() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"c\",\"b\",\"a\"]}", 2);
		assertEquals(List.of("b", "c"), select(interceptor, List.of("a", "b", "c", "d")));
	}

	@Test
	void unavailableNamesDoNotConsumeSlots() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"missing\",\"a\"]}", 1, "missing");
		assertEquals(List.of("a"), select(interceptor, List.of("a", "b", "c")));
	}

	@Test
	void requestWithinTheLimitIsNotFiltered() {
		SelectionModel model = new SelectionModel("{\"tools\":[]}");
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder().chatClient(model).maxTools(2).build();
		ModelRequest request = request(List.of("a", "b"));
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, filtered -> {
			captured.set(filtered);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("done"));
		});
		assertSame(request, captured.get());
		assertEquals(0, model.calls);
	}

	@ParameterizedTest
	@ValueSource(strings = { "not json", "", "null", "{}", "{\"tools\":null}", "{\"tools\":[null]}",
			"{\"tools\":\"a\"}", "{\"tools\":[1]}", "{\"tools\":[true]}", "{\"tools\":[{}]}", "{\"tools\":[[]]}",
			"{\"tools\":[\"\"]}", "{\"tools\":[\" \"]}", "{\"tools\":[\"a\"]} {}" })
	void malformedSelectionKeepsEveryAvailableTool(String json) {
		assertEquals(List.of("a", "b", "c"), select(selection(json, 1), List.of("a", "b", "c")));
	}

	@Test
	void failedTurnKeepsEveryAvailableTool() {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(client)
			.maxTools(1)
			.build();
		assertEquals(List.of("a", "b", "c"), select(interceptor, List.of("a", "b", "c")));
	}

	@Test
	void emptySelectionKeepsOnlyMandatoryTools() {
		assertEquals(List.of(), select(selection("{\"tools\":[]}", 1), List.of("a", "b")));
		assertEquals(List.of("b"), select(selection("{\"tools\":[]}", 1, "b"), List.of("a", "b")));
	}

	@Test
	void duplicatesDoNotConsumeSlotsAndNoLimitKeepsEveryRankedTool() {
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(new SelectionModel("{\"tools\":[\"b\",\"b\",\"a\"]}"))
			.build();
		assertEquals(List.of("a", "b"), select(interceptor, List.of("a", "b", "c")));
	}

	@Test
	void emptyToolsAndMissingUserMessageSkipSelection() {
		SelectionModel client = new SelectionModel("{\"tools\":[\"a\"]}");
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(client)
			.maxTools(1)
			.build();
		assertEquals(List.of(), select(interceptor, List.of()));
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.assistant("No user query")))
			.tools(List.of("a", "b"))
			.build();
		interceptor.interceptModel(request, original -> {
			assertSame(request, original);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("done"));
		});
		assertEquals(0, client.calls);
		assertEquals("ToolSelection", interceptor.getName());
	}

	@Test
	void validatesRequiredClientAndPositiveLimit() {
		assertThrows(IllegalStateException.class, () -> ToolSelectionInterceptor.builder().build());
		assertThrows(IllegalArgumentException.class, () -> ToolSelectionInterceptor.builder().maxTools(0));
		assertThrows(IllegalArgumentException.class, () -> ToolSelectionInterceptor.builder().maxTools(-1));
	}

	private static ToolSelectionInterceptor selection(String json, int max, String... mandatory) {
		return ToolSelectionInterceptor.builder()
			.chatClient(new SelectionModel(json))
			.maxTools(max)
			.alwaysInclude(mandatory)
			.build();
	}

	private static ModelRequest request(List<String> tools) {
		return ModelRequest.builder().messages(List.of(ChatMessage.user("Choose relevant tools"))).tools(tools).build();
	}

	private static List<String> select(ToolSelectionInterceptor interceptor, List<String> tools) {
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request(tools), filtered -> {
			captured.set(filtered);
			return ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("done"));
		});
		return captured.get().getTools();
	}

	private static final class SelectionModel implements ChatClient {

		private final String json;

		private int calls;

		private SelectionModel(String json) {
			this.json = json;
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest prompt) {
			calls++;
			return CompletableFuture.completedFuture(new ChatResponse(null, null, List.of(ChatMessage.assistant(json)),
					List.of(), null, null, Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new AssertionError("Tool selection must use a single non-streaming turn");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
