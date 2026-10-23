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
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ModelRequest toolDescriptions functionality. Tests the new
 * toolDescriptions field added to support enhanced tool selection.
 */
class ModelRequestTest {

	@Test
	void absentSelectionIncludesStaticAndDynamicToolsAndCopiesRetainBindings() {
		Tool staticTool = tool("lookup");
		Tool dynamicTool = tool("clock");
		List<Tool> input = new ArrayList<>(List.of(staticTool));
		ToolRegistry registry = new ToolRegistry(input);
		ModelRequest original = ModelRequest.builder()
			.toolRegistry(registry)
			.dynamicToolRegistry(new ToolRegistry(List.of(tool("lookup"), dynamicTool)))
			.build();
		input.clear();
		ModelRequest copy = ModelRequest.builder(original).build();
		assertEquals(List.of("lookup", "clock"), original.getTools());
		assertEquals(original.getTools(), copy.getTools());
		assertFalse(copy.hasToolSelection());
		assertEquals(staticTool, copy.getToolRegistry().find("lookup").orElseThrow());
		assertEquals(dynamicTool, copy.getDynamicToolRegistry().find("clock").orElseThrow());
		assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
		assertThrows(UnsupportedOperationException.class, () -> original.getTools().clear());
		ModelRequest disabled = ModelRequest.builder(copy).tools(List.of()).build();
		assertTrue(disabled.hasToolSelection());
		assertTrue(disabled.getTools().isEmpty());
		assertEquals(List.of("lookup", "clock"), original.getTools());
	}

	@Test
	void nullRegistriesAreRejectedAndPublicSignaturesAreFrameworkNeutral() {
		assertThrows(NullPointerException.class, () -> ModelRequest.builder().toolRegistry(null));
		assertThrows(NullPointerException.class, () -> ModelRequest.builder().dynamicToolRegistry(null));
		for (Class<?> type : List.of(ModelRequest.class, ModelRequest.Builder.class)) {
			for (var method : type.getDeclaredMethods()) {
				assertFalse(method.toGenericString().contains("org.springframework.ai"), method.toGenericString());
			}
			for (var constructor : type.getConstructors()) {
				assertFalse(constructor.toGenericString().contains("org.springframework.ai"),
						constructor.toGenericString());
			}
		}
	}

	private static Tool tool(String name) {
		Tool tool = mock(Tool.class);
		when(tool.definition()).thenReturn(new ToolDefinition(name, "Description of " + name, "{}"));
		return tool;
	}

	@Test
	void systemMessageMustHaveSystemRole() {
		assertThrows(IllegalArgumentException.class,
				() -> ModelRequest.builder().systemMessage(ChatMessage.user("not system")).build());
	}

	@Test
	void messagesAreIsolatedAcrossInputCopiesAndBuilderReuse() {
		List<ChatMessage> input = new ArrayList<>(List.of(ChatMessage.user("original")));
		ModelRequest.Builder builder = ModelRequest.builder()
			.messages(input)
			.tools(List.of("search"))
			.toolDescriptions(Map.of("search", "Search"));
		ModelRequest first = builder.build();
		input.clear();
		assertEquals(List.of(ChatMessage.user("original")), first.getMessages());
		ModelRequest copy = ModelRequest.builder(first).build();
		first.getMessages().clear();
		assertEquals(List.of(ChatMessage.user("original")), copy.getMessages());
		builder.tools(List.of()).toolDescriptions(Map.of("search", "Changed"));
		assertEquals(List.of("search"), first.getTools());
		assertEquals("Search", first.getToolDescriptions().get("search"));
		assertThrows(UnsupportedOperationException.class, () -> first.getTools().clear());
		assertThrows(UnsupportedOperationException.class, () -> first.getToolDescriptions().clear());
	}

	@Test
	void runtimeContextCopiesAreIsolatedButRemainEditable() {
		ModelRequest.Builder builder = ModelRequest.builder().context(Map.of("step", 1));
		ModelRequest first = builder.build();
		ModelRequest second = builder.build();
		first.getContext().put("step", 2);
		assertEquals(1, second.getContext().get("step"));
		ModelRequest copy = ModelRequest.builder(first).build();
		first.getContext().clear();
		assertEquals(2, copy.getContext().get("step"));
	}

	@Test
	void absentToolSelectionAndExplicitEmptySelectionRemainDistinctAcrossCopies() {
		ModelRequest absent = ModelRequest.builder().build();
		assertFalse(absent.hasToolSelection());
		assertFalse(ModelRequest.builder(absent).build().hasToolSelection());
		ModelRequest empty = ModelRequest.builder(absent).tools(List.of()).build();
		assertTrue(empty.hasToolSelection());
		assertTrue(ModelRequest.builder(empty).build().hasToolSelection());
		assertTrue(empty.getTools().isEmpty());
	}

	@Test
	void runtimeContextIsNotAnInvocationAttribute() {
		ModelRequest request = ModelRequest.builder().context(Map.of("messages", List.of("private state"))).build();
		assertEquals(List.of("private state"), request.getContext().get("messages"));
		assertTrue(request.getAttributes().isEmpty());
	}

	@Test
	void explicitAttributesAreImmutableAndSurviveCopies() {
		Map<String, Object> attributes = new HashMap<>(Map.of("correlation", "call-1"));
		ModelRequest original = ModelRequest.builder().attributes(attributes).context(Map.of("thread", "t-1")).build();
		attributes.clear();
		ModelRequest copy = ModelRequest.builder(original).context(Map.of("thread", "t-2")).build();
		assertEquals(Map.of("correlation", "call-1"), original.getAttributes());
		assertEquals(original.getAttributes(), copy.getAttributes());
		assertEquals("t-1", original.getContext().get("thread"));
		assertEquals("t-2", copy.getContext().get("thread"));
		assertThrows(UnsupportedOperationException.class, () -> copy.getAttributes().clear());
	}

	@Test
	void testModelRequestWithToolDescriptions() {
		// Create tool descriptions map
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("weather_tool", "Get current weather information for a city");
		toolDescriptions.put("ticket_tool", "Book train or flight tickets");
		toolDescriptions.put("hotel_tool", "Search and book hotel rooms");

		List<String> toolNames = List.of("weather_tool", "ticket_tool", "hotel_tool");

		// Build ModelRequest with tool descriptions
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("What's the weather in Beijing?")))
			.tools(toolNames)
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Verify tool descriptions are set correctly
		assertNotNull(request.getToolDescriptions());
		assertEquals(3, request.getToolDescriptions().size());
		assertEquals("Get current weather information for a city", request.getToolDescriptions().get("weather_tool"));
		assertEquals("Book train or flight tickets", request.getToolDescriptions().get("ticket_tool"));
		assertEquals("Search and book hotel rooms", request.getToolDescriptions().get("hotel_tool"));
	}

	@Test
	void testModelRequestWithoutToolDescriptions() {
		// Build ModelRequest without tool descriptions
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Hello")))
			.tools(List.of("tool1", "tool2"))
			.context(new HashMap<>())
			.build();

		// Verify toolDescriptions defaults to empty map when not set
		assertNotNull(request.getToolDescriptions());
		assertTrue(request.getToolDescriptions().isEmpty());
	}

	@Test
	void testModelRequestBuilderCopiesToolDescriptions() {
		// Create original request with tool descriptions
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("search", "Search for information");
		toolDescriptions.put("calculate", "Perform calculations");

		ModelRequest original = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test")))
			.tools(List.of("search", "calculate"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Create new request from original using builder(ModelRequest)
		ModelRequest copy = ModelRequest.builder(original).build();

		// Verify tool descriptions are copied
		assertNotNull(copy.getToolDescriptions());
		assertEquals(original.getToolDescriptions(), copy.getToolDescriptions());
		assertEquals("Search for information", copy.getToolDescriptions().get("search"));
		assertEquals("Perform calculations", copy.getToolDescriptions().get("calculate"));
	}

	@Test
	void testModelRequestBuilderModifyToolDescriptions() {
		// Create original request
		Map<String, String> originalDescriptions = new HashMap<>();
		originalDescriptions.put("tool1", "Original description");

		ModelRequest original = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test")))
			.tools(List.of("tool1", "tool2"))
			.toolDescriptions(originalDescriptions)
			.context(new HashMap<>())
			.build();

		// Create modified request with different tool descriptions
		Map<String, String> newDescriptions = new HashMap<>();
		newDescriptions.put("tool1", "Modified description");
		newDescriptions.put("tool2", "New tool description");

		ModelRequest modified = ModelRequest.builder(original).toolDescriptions(newDescriptions).build();

		// Verify original is unchanged
		assertEquals("Original description", original.getToolDescriptions().get("tool1"));
		assertNull(original.getToolDescriptions().get("tool2"));

		// Verify modified has new descriptions
		assertEquals("Modified description", modified.getToolDescriptions().get("tool1"));
		assertEquals("New tool description", modified.getToolDescriptions().get("tool2"));
	}

	@Test
	void testModelRequestWithEmptyToolDescriptions() {
		// Build ModelRequest with empty tool descriptions map
		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test")))
			.tools(List.of("tool1"))
			.toolDescriptions(new HashMap<>())
			.context(new HashMap<>())
			.build();

		// Verify empty map is preserved
		assertNotNull(request.getToolDescriptions());
		assertTrue(request.getToolDescriptions().isEmpty());
	}

	@Test
	void testModelRequestWithSystemMessage() {
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("helper", "A helpful assistant tool");

		ModelRequest request = ModelRequest.builder()
			.systemMessage(ChatMessage.system("You are a helpful assistant"))
			.messages(List.of(ChatMessage.user("Help me")))
			.tools(List.of("helper"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Verify all fields are set correctly
		assertNotNull(request.getSystemMessage());
		assertEquals("You are a helpful assistant", request.getSystemMessage().text());
		assertNotNull(request.getToolDescriptions());
		assertEquals("A helpful assistant tool", request.getToolDescriptions().get("helper"));
	}

	@Test
	void testModelRequestToolDescriptionsWithSpecialCharacters() {
		// Test with descriptions containing special characters
		Map<String, String> toolDescriptions = new HashMap<>();
		toolDescriptions.put("code_tool", "Execute code: supports Python, Java, and JavaScript");
		toolDescriptions.put("search_tool", "Search for results (max 10 items)");
		toolDescriptions.put("translate_tool", "Translate text between languages - supports 50+ languages");

		ModelRequest request = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("Test")))
			.tools(List.of("code_tool", "search_tool", "translate_tool"))
			.toolDescriptions(toolDescriptions)
			.context(new HashMap<>())
			.build();

		// Verify descriptions with special characters are preserved
		assertEquals("Execute code: supports Python, Java, and JavaScript",
				request.getToolDescriptions().get("code_tool"));
		assertEquals("Search for results (max 10 items)", request.getToolDescriptions().get("search_tool"));
		assertEquals("Translate text between languages - supports 50+ languages",
				request.getToolDescriptions().get("translate_tool"));
	}

}
