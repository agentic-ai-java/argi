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
package io.github.agentic.ai.graph.plain_text;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ArgiJacksonStateSerializerTest {

	private final ArgiJacksonStateSerializer serializer = new ArgiJacksonStateSerializer(OverAllState::new);

	@Test
	void testSystemMessageSerialization() throws Exception {
		roundTripRole(ChatRole.SYSTEM);
	}

	@Test
	void testUserMessageSerialization() throws Exception {
		roundTripRole(ChatRole.USER);
	}

	@Test
	void testAssistantMessageSerialization() throws Exception {
		roundTripRole(ChatRole.ASSISTANT);
	}

	private void roundTripRole(ChatRole role) throws Exception {
		ChatMessage original = ChatMessage.builder(role)
			.text("message")
			.metadata(Map.of("source", "test", "priority", 1))
			.build();
		assertEquals(original, copy(original));
	}

	@Test
	void testToolResponseMessageSerialization() throws Exception {
		List<ChatMessage> results = List.of(ChatMessage.tool("call-1", "calculator", "42"),
				ChatMessage.tool("call-2", "weather", "25"));
		assertEquals(results, copy(results));
	}

	@Test
	void testApplicationDocumentSerialization() throws Exception {
		ApplicationDocument document = new ApplicationDocument("doc", "content", 0.95,
				Map.of("source", "file.pdf", "page", 1));
		assertEquals(document, copy(document));
	}

	@Test
	void testApplicationDocumentWithoutOptionalFields() throws Exception {
		ApplicationDocument document = new ApplicationDocument("minimal", "content", null, Map.of());
		assertEquals(document, copy(document));
	}

	@Test
	void testMultipleMessagesSerialization() throws Exception {
		Map<String, Object> mixed = Map.of("system", ChatMessage.system("system"), "user", ChatMessage.user("user"),
				"messages", List.of(ChatMessage.system("system"), ChatMessage.user("user")), "doc",
				new ApplicationDocument("doc", "content", 0.8, Map.of()));
		assertEquals(mixed, copy(mixed));
	}

	@Test
	void shouldPreserveTypedListElementsInStateValue() throws Exception {
		Plan plan = copy(new Plan(List.of(new PlanStep("search"), new PlanStep("write"))));
		assertEquals(2, plan.steps().size());
		assertInstanceOf(PlanStep.class, plan.steps().get(0));
		assertEquals("search", plan.steps().get(0).title());
		assertEquals("write", plan.steps().get(1).title());
	}

	@Test
	void testComplexMetadataSerialization() throws Exception {
		ChatMessage message = ChatMessage.user("complex")
			.withMetadata(Map.of("string", "value", "number", 42, "boolean", true, "list", List.of("first", "second"),
					"nested", Map.of("key", "value")));
		assertEquals(message, copy(message));
	}

	@Test
	void testNoDoubleClassField() throws Exception {
		for (ChatRole role : List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT)) {
			ChatMessage message = ChatMessage.builder(role).text("response").metadata(Map.of("key", "value")).build();
			String json = serializer.objectMapper().writeValueAsString(Map.of("object", message));
			// Strict duplicate detection applies to individual objects, not repeated
			// class names in a list.
			com.fasterxml.jackson.core.JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
				.enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.build();
			try (var parser = factory.createParser(json)) {
				while (parser.nextToken() != null) {
				}
			}
			assertEquals(message, copy(message));
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T copy(T object) throws Exception {
		return (T) serializer.dataFromBytes(serializer.dataToBytes(Map.of("object", object))).get("object");
	}

	public record ApplicationDocument(String id, String text, Double score, Map<String, Object> metadata) {
	}

	public static class Plan {

		private List<PlanStep> steps;

		@SuppressWarnings("unused")
		public Plan() {
		}

		public Plan(List<PlanStep> steps) {
			this.steps = steps;
		}

		public List<PlanStep> steps() {
			return steps;
		}

	}

	public static final class PlanStep {

		private String title;

		@SuppressWarnings("unused")
		public PlanStep() {
		}

		public PlanStep(String title) {
			this.title = title;
		}

		public String title() {
			return title;
		}

	}

}
