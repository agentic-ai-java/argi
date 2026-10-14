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
import io.github.agentic.ai.graph.agent.node.AgentLlmNode;
import io.github.agentic.ai.graph.serializer.AgentInstructionMessage;
import io.github.agentic.ai.graph.utils.PromptTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInstructionTemplateTests {

	@Test
	void rendersOnlyTheLatestPendingInstructionAndKeepsMetadata() {
		ChatMessage earlier = AgentInstructionMessage.builder().text("Earlier {name}").build();
		List<ChatMessage> messages = new ArrayList<>(List.of(earlier,
				AgentInstructionMessage.builder()
					.text("Hello {name}: {answer}")
					.metadata(Map.of("source", "instruction"))
					.build()));
		AgentLlmNode node = AgentLlmNode.builder().build();
		node.renderTemplatedUserMessage(messages, Map.of("name", "Ada", "answer", ChatMessage.assistant("ready")),
				Optional.empty());

		assertSame(earlier, messages.get(0));
		ChatMessage rendered = messages.get(1);
		assertEquals("Hello Ada: ready", rendered.text());
		assertEquals("instruction", rendered.metadata().get("source"));
		assertTrue(AgentInstructionMessage.isRendered(rendered));
	}

	@Test
	void doesNotRenderInstructionTwiceOrRewriteOrdinaryUserMessages() {
		ChatMessage user = ChatMessage.user("Literal {name}");
		List<ChatMessage> messages = new ArrayList<>(
				List.of(user, AgentInstructionMessage.builder().text("Hello {name}").build()));
		AgentLlmNode node = AgentLlmNode.builder().build();
		node.renderTemplatedUserMessage(messages, Map.of("name", "{unresolved}"), Optional.empty());
		node.renderTemplatedUserMessage(messages, Map.of("name", "different"), Optional.empty());
		assertSame(user, messages.get(0));
		assertEquals("Hello {unresolved}", messages.get(1).text());
	}

	@Test
	void rendererReceivesFilteredVariablesAndCanUseAnotherTemplateEngine() {
		AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
		AgentLlmNode node = AgentLlmNode.builder().templateRenderer((text, variables) -> {
			captured.set(variables);
			return text.replace("${answer}", variables.get("answer").toString());
		}).build();
		List<ChatMessage> messages = new ArrayList<>(
				List.of(AgentInstructionMessage.builder().text("Answer: ${answer}").build()));
		node.renderTemplatedUserMessage(messages,
				Map.of("answer", ChatMessage.assistant("ready"), "messages",
						List.of(ChatMessage.user("private history")), "otherList", List.of("ignored"), "count", 3),
				Optional.empty());
		assertEquals("Answer: ready", messages.get(0).text());
		assertEquals(Map.of("answer", "ready", "count", 3), captured.get());
	}

	@Test
	void acceptsCustomDelimitersWithoutSpringAiRenderer() {
		AgentLlmNode node = AgentLlmNode.builder()
			.templateRenderer((text, variables) -> PromptTemplate.of(text, '<', '>').render(variables))
			.build();
		List<ChatMessage> messages = new ArrayList<>(
				List.of(AgentInstructionMessage.builder().text("Hello <name>; {literal}").build()));
		node.renderTemplatedUserMessage(messages, Map.of("name", "Ada"), Optional.empty());
		assertEquals("Hello Ada; {literal}", messages.get(0).text());
		assertInstanceOf(DefaultBuilder.class, ReactAgent.builder().templateRenderer((text, variables) -> text));
	}

	@Test
	void missingVariablesAndRendererFailuresDoNotMarkAnInstructionRendered() {
		List<ChatMessage> messages = new ArrayList<>(
				List.of(AgentInstructionMessage.builder().text("Hello {name}").build()));
		AgentLlmNode defaultNode = AgentLlmNode.builder().build();
		assertThrows(IllegalArgumentException.class,
				() -> defaultNode.renderTemplatedUserMessage(messages, null, Optional.empty()));
		assertFalse(AgentInstructionMessage.isRendered(messages.get(0)));

		AgentLlmNode failing = AgentLlmNode.builder().templateRenderer((text, variables) -> {
			throw new IllegalStateException("Renderer unavailable");
		}).build();
		assertThrows(IllegalStateException.class,
				() -> failing.renderTemplatedUserMessage(messages, Map.of(), Optional.empty()));
		AgentLlmNode nullRenderer = AgentLlmNode.builder().templateRenderer((text, variables) -> null).build();
		assertThrows(NullPointerException.class,
				() -> nullRenderer.renderTemplatedUserMessage(messages, Map.of(), Optional.empty()));
		assertFalse(AgentInstructionMessage.isRendered(messages.get(0)));
	}

	@Test
	void schemaAugmentationPreservesLiteralJsonAndIsIdempotent() {
		List<ChatMessage> messages = new ArrayList<>(
				List.of(AgentInstructionMessage.builder().text("Hello {name}").build()));
		AgentLlmNode node = AgentLlmNode.builder().build();
		String schema = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}";
		node.augmentUserMessage(messages, schema);
		node.augmentUserMessage(messages, schema);
		node.renderTemplatedUserMessage(messages, Map.of("name", "Ada"), Optional.empty());
		assertEquals("Hello Ada" + System.lineSeparator() + schema, messages.get(0).text());
	}

	@Test
	void aSharedNodeRendersConcurrentCallsWithIsolatedVariables() {
		AgentLlmNode node = AgentLlmNode.builder().build();
		List<CompletableFuture<String>> calls = IntStream.range(0, 100)
			.mapToObj(index -> CompletableFuture.supplyAsync(() -> {
				List<ChatMessage> messages = new ArrayList<>(
						List.of(AgentInstructionMessage.builder().text("{value}:{value}").build()));
				node.renderTemplatedUserMessage(messages, Map.of("value", index), Optional.empty());
				return messages.get(0).text();
			}))
			.toList();
		for (int index = 0; index < calls.size(); index++) {
			assertEquals(index + ":" + index, calls.get(index).join());
		}
	}

}
