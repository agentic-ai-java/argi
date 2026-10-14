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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.toolemulator.ToolEmulatorInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolEmulatorInterceptorTests {

	private ChatClient client;

	private ToolCallHandler handler;

	private final ToolCallRequest request = ToolCallRequest.builder()
		.toolName("weather")
		.toolCallId("call-17")
		.arguments("{\"city\":\"Shanghai\"}")
		.build();

	@BeforeEach
	void setUp() {
		client = mock(ChatClient.class);
		handler = mock(ToolCallHandler.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response("Sunny")));
	}

	@Test
	void emulatesAllByDefaultAndPreservesToolIdentity() {
		ToolEmulatorInterceptor interceptor = ToolEmulatorInterceptor.builder().chatClient(client).build();
		ToolCallResponse result = interceptor.interceptToolCall(request, handler);
		assertThat(result.getResult()).isEqualTo("Sunny");
		assertThat(result.getToolName()).isEqualTo("weather");
		assertThat(result.getToolCallId()).isEqualTo("call-17");
		assertThat(result.isError()).isFalse();
		assertThat(interceptor.getName()).isEqualTo("ToolEmulator");
		ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(captured.capture());
		assertThat(captured.getValue().messages().get(0).text()).contains("weather", request.getArguments());
		assertThat(captured.getValue().tools()).isEmpty();
		verifyNoInteractions(handler);
	}

	@Test
	void selectiveModeEmulatesNamedToolsAndUsesCustomPrompt() {
		ToolEmulatorInterceptor interceptor = ToolEmulatorInterceptor.builder()
			.chatClient(client)
			.emulateAllTools(false)
			.addTool("lookup")
			.addTools(List.of("weather", "search"))
			.promptTemplate("Emulate %s (%s) with %s")
			.build();
		assertThat(interceptor.interceptToolCall(request, handler).getResult()).isEqualTo("Sunny");
		ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(captured.capture());
		assertThat(captured.getValue().messages().get(0).text())
			.isEqualTo("Emulate weather (No description available) with " + request.getArguments());
		verifyNoInteractions(handler);
	}

	@Test
	void unlistedToolsExecuteNormally() {
		ToolCallResponse actual = ToolCallResponse.of("call-17", "weather", "Rainy");
		when(handler.call(request)).thenReturn(actual);
		ToolEmulatorInterceptor interceptor = ToolEmulatorInterceptor.builder()
			.chatClient(client)
			.emulateAllTools(false)
			.addTool("lookup")
			.build();
		assertThat(interceptor.interceptToolCall(request, handler)).isSameAs(actual);
		verifyNoInteractions(client);
	}

	@Test
	void failedTurnFallsBackToActualExecution() {
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));
		assertFallback();
	}

	@Test
	void blankResultFallsBackToActualExecution() {
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response(" \n")));
		assertFallback();
	}

	@Test
	void longResultIsNotTruncated() {
		String text = "Forecast ".repeat(30);
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response(text)));
		assertThat(ToolEmulatorInterceptor.builder()
			.chatClient(client)
			.build()
			.interceptToolCall(request, handler)
			.getResult()).isEqualTo(text);
	}

	@Test
	void requiresAChatClient() {
		assertThatThrownBy(() -> ToolEmulatorInterceptor.builder().build()).isInstanceOf(IllegalStateException.class);
	}

	private void assertFallback() {
		ToolCallResponse actual = ToolCallResponse.of("call-17", "weather", "Actual forecast");
		when(handler.call(request)).thenReturn(actual);
		assertThat(ToolEmulatorInterceptor.builder().chatClient(client).build().interceptToolCall(request, handler))
			.isSameAs(actual);
		verify(handler).call(request);
	}

	private ChatResponse response(String text) {
		return new ChatResponse(null, null, List.of(ChatMessage.assistant(text)), List.of(), null, null, Map.of());
	}

}
