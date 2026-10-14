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

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionInterceptor;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ToolSelectionInterceptor with tool descriptions. Verifies that tool
 * descriptions are properly included in the selection prompt.
 */
class ToolSelectionWithDescriptionsTest {

	@Mock
	private ChatClient selectionClient;

	@Mock
	private ModelCallHandler handler;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		when(selectionClient.callBlocking(any(ChatRequest.class))).thenCallRealMethod();
	}

	@Test
	void testToolSelectionPromptIncludesDescriptions() {
		// Setup mock response for tool selection
		ChatResponse mockResponse = createMockChatResponse("{\"tools\": [\"weather_tool\"]}");
		when(selectionClient.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(mockResponse));

		// Setup mock handler response
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Weather is sunny")));

		// Create interceptor
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(2)
			.build();

		// Create request with tool descriptions
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("weather_tool", "Get current weather information for any city worldwide");
		toolDescriptions.put("ticket_tool", "Book train or flight tickets for travel");
		toolDescriptions.put("hotel_tool", "Search and reserve hotel accommodations");

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("What's the weather in Shanghai?")))
			.tools(List.of("weather_tool", "ticket_tool", "hotel_tool"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Execute interceptor
		interceptor.interceptModel(request, handler);

		// Capture the prompt sent to selection model
		ArgumentCaptor<ChatRequest> promptCaptor = ArgumentCaptor.forClass(ChatRequest.class);
		verify(selectionClient, times(1)).call(promptCaptor.capture());

		// Verify prompt contains tool descriptions
		ChatRequest capturedPrompt = promptCaptor.getValue();
		String promptContent = promptText(capturedPrompt);

		assertTrue(promptContent.contains("weather_tool"), "Prompt should contain weather_tool name");
		assertTrue(promptContent.contains("Get current weather information for any city worldwide"),
				"Prompt should contain weather_tool description");
		assertTrue(promptContent.contains("ticket_tool"), "Prompt should contain ticket_tool name");
		assertTrue(promptContent.contains("Book train or flight tickets for travel"),
				"Prompt should contain ticket_tool description");
		assertTrue(promptContent.contains("hotel_tool"), "Prompt should contain hotel_tool name");
		assertTrue(promptContent.contains("Search and reserve hotel accommodations"),
				"Prompt should contain hotel_tool description");
	}

	@Test
	void testToolSelectionWithPartialDescriptions() {
		// Setup mock response
		ChatResponse mockResponse = createMockChatResponse("{\"tools\": [\"tool_with_desc\"]}");
		when(selectionClient.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(mockResponse));
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Done")));

		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(1) // Changed from 2 to 1 to trigger selection
			.build();

		// Create request with partial descriptions (some tools have descriptions, some
		// don't)
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("tool_with_desc", "This tool has a description");
		// tool_without_desc intentionally not added

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test query")))
			.tools(List.of("tool_with_desc", "tool_without_desc", "tool_third")) // Added
																					// third
																					// tool
																					// to
																					// exceed
																					// maxTools
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Execute interceptor
		interceptor.interceptModel(request, handler);

		// Capture the prompt
		ArgumentCaptor<ChatRequest> promptCaptor = ArgumentCaptor.forClass(ChatRequest.class);
		verify(selectionClient).call(promptCaptor.capture());

		String promptContent = promptText(promptCaptor.getValue());

		// Tool with description should have description in prompt
		assertTrue(promptContent.contains("tool_with_desc: This tool has a description"),
				"Tool with description should show description");

		// Tool without description should still be listed (just without description)
		assertTrue(promptContent.contains("tool_without_desc"), "Tool without description should still be listed");
	}

	@Test
	void testToolSelectionWithNullDescriptions() {
		// Setup mock response
		ChatResponse mockResponse = createMockChatResponse("{\"tools\": [\"tool1\"]}");
		when(selectionClient.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(mockResponse));
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Done")));

		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(1)
			.build();

		// Create request without tool descriptions (null)
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test query")))
			.tools(List.of("tool1", "tool2", "tool3"))
			.context(new HashMap<>())
			.build();

		// Execute interceptor - should not fail
		ModelResponse response = interceptor.interceptModel(request, handler);

		// Verify it still works
		assertNotNull(response);

		// Capture the prompt
		ArgumentCaptor<ChatRequest> promptCaptor = ArgumentCaptor.forClass(ChatRequest.class);
		verify(selectionClient).call(promptCaptor.capture());

		String promptContent = promptText(promptCaptor.getValue());

		// Tools should be listed without descriptions
		assertTrue(promptContent.contains("tool1"), "tool1 should be in prompt");
		assertTrue(promptContent.contains("tool2"), "tool2 should be in prompt");
		assertTrue(promptContent.contains("tool3"), "tool3 should be in prompt");
	}

	@Test
	void testToolSelectionWithEmptyDescription() {
		// Setup mock response
		ChatResponse mockResponse = createMockChatResponse("{\"tools\": [\"tool1\"]}");
		when(selectionClient.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(mockResponse));
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Done")));

		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(1)
			.build();

		// Create request with empty string description
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("tool1", "Valid description");
		toolDescriptions.put("tool2", ""); // Empty description
		toolDescriptions.put("tool3", null); // Null description

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test query")))
			.tools(List.of("tool1", "tool2", "tool3"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Execute interceptor
		interceptor.interceptModel(request, handler);

		// Capture the prompt
		ArgumentCaptor<ChatRequest> promptCaptor = ArgumentCaptor.forClass(ChatRequest.class);
		verify(selectionClient).call(promptCaptor.capture());

		String promptContent = promptText(promptCaptor.getValue());

		// tool1 should have description
		assertTrue(promptContent.contains("tool1: Valid description"), "tool1 should have its description");
	}

	@Test
	void testToolSelectionSkippedWhenToolsWithinLimit() {
		// Create interceptor with maxTools = 5
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(5)
			.build();

		// Create handler response
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Done")));

		// Create request with only 2 tools (less than maxTools)
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("tool1", "Description 1");
		toolDescriptions.put("tool2", "Description 2");

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test query")))
			.tools(List.of("tool1", "tool2"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Execute interceptor
		interceptor.interceptModel(request, handler);

		// Selection model should NOT be called when tools <= maxTools
		verify(selectionClient, times(0)).call(any(ChatRequest.class));

		// Handler should be called directly with original request
		verify(handler, times(1)).call(request);
	}

	@Test
	void testToolSelectionFiltersToolsCorrectly() {
		// Setup mock to return specific tools
		ChatResponse mockResponse = createMockChatResponse("{\"tools\": [\"weather_tool\", \"hotel_tool\"]}");
		when(selectionClient.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(mockResponse));
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Done")));

		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(2)
			.build();

		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("weather_tool", "Weather info");
		toolDescriptions.put("ticket_tool", "Ticket booking");
		toolDescriptions.put("hotel_tool", "Hotel booking");

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("I need weather and hotel info")))
			.tools(List.of("weather_tool", "ticket_tool", "hotel_tool"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Execute interceptor
		interceptor.interceptModel(request, handler);

		// Capture the request passed to handler
		ArgumentCaptor<ModelRequest> requestCaptor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler).call(requestCaptor.capture());

		ModelRequest filteredRequest = requestCaptor.getValue();

		// Verify only selected tools are passed to handler
		assertEquals(2, filteredRequest.getTools().size());
		assertTrue(filteredRequest.getTools().contains("weather_tool"));
		assertTrue(filteredRequest.getTools().contains("hotel_tool"));
	}

	@Test
	void testToolSelectionPromptFormat() {
		// Setup mock response
		ChatResponse mockResponse = createMockChatResponse("{\"tools\": [\"api_tool\"]}");
		when(selectionClient.call(any(ChatRequest.class))).thenReturn(CompletableFuture.completedFuture(mockResponse));
		when(handler.call(any(ModelRequest.class)))
			.thenReturn(ModelResponse.of(io.github.agentic.ai.api.chat.ChatMessage.assistant("Done")));

		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
			.chatClient(selectionClient)
			.maxTools(1)
			.build();

		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("api_tool", "Call external API endpoints");

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Call the API")))
			.tools(List.of("api_tool", "db_tool"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ChatRequest> promptCaptor = ArgumentCaptor.forClass(ChatRequest.class);
		verify(selectionClient).call(promptCaptor.capture());

		String promptContent = promptText(promptCaptor.getValue());

		// Verify format: "- toolName: description"
		assertTrue(promptContent.contains("- api_tool: Call external API endpoints"),
				"Prompt should use format '- toolName: description'");
	}

	private ChatResponse createMockChatResponse(String content) {
		return new ChatResponse(null, null, List.of(ChatMessage.assistant(content)), List.of(), null, null, Map.of());
	}

	private String promptText(ChatRequest request) {
		return request.messages().stream().map(ChatMessage::text).collect(Collectors.joining("\n"));
	}

}
