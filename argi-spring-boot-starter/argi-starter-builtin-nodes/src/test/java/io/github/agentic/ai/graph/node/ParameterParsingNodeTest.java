/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.graph.NodeOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static io.github.agentic.ai.graph.node.QuestionClassifierNodeTest.response;
import static io.github.agentic.ai.graph.node.QuestionClassifierNodeTest.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParameterParsingNodeTest {

	private final ChatClient client = mock(ChatClient.class);

	private ParameterParsingNode.Builder builder() {
		return ParameterParsingNode.builder()
			.chatClient(client)
			.inputTextKey("input")
			.parameters(List.of(ParameterParsingNode.param("name", "String", "Person name"),
					ParameterParsingNode.param("age", "Number", "Person age")));
	}

	@Test
	void extractsDataAndRendersInstructionWithOrderedMessagesAndUsage() {
		ChatResponse response = response(
				"{\"is_success\":true,\"data\":{\"name\":\"Alex\",\"age\":20},\"reason\":\"success\"}");
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response));
		Map<String, Object> result = builder().successKey("success")
			.dataKey("details")
			.reasonKey("explanation")
			.instruction("{instruction}")
			.build()
			.apply(state(Map.of("input", "Alex is 20", "instruction", "Extract age")))
			.join();
		assertEquals(Map.of("success", true, "details", Map.of("name", "Alex", "age", 20), "explanation", "success",
				NodeOutput.TOKEN_USAGE_KEY, response.usage()), result);
		ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
		verify(client).call(request.capture());
		assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT,
				ChatRole.USER), request.getValue().messages().stream().map(message -> message.role()).toList());
		assertEquals("Alex is 20", request.getValue().messages().get(5).text());
		assertTrue(request.getValue().messages().get(0).text().contains("Extract age"));
		assertTrue(request.getValue().messages().get(0).text().contains("Person age"));
		assertTrue(request.getValue().tools().isEmpty());
	}

	@Test
	void copiesParametersAndUsesConfiguredFallbackInput() {
		List<ParameterParsingNode.Param> parameters = new ArrayList<>(
				List.of(ParameterParsingNode.param("note", "String", "Note")));
		ParameterParsingNode node = builder().parameters(parameters).inputText("Fallback").build();
		parameters.clear();
		when(client.call(any())).thenReturn(CompletableFuture
			.completedFuture(response("```\n{\"is_success\":true,\"data\":{\"note\":\"Keep ```json intact\"}}\n```")));
		Map<String, Object> result = node.apply(state(Map.of())).join();
		assertEquals(Map.of("note", "Keep ```json intact"), result.get("data"));
		assertEquals(true,
				builder().inputTextKey(null)
					.inputText("Static")
					.build()
					.apply(state(Map.of()))
					.join()
					.get("is_success"));
		assertEquals(true,
				builder().inputTextKey(" ")
					.inputText("Static")
					.build()
					.apply(state(Map.of()))
					.join()
					.get("is_success"));
	}

	@Test
	void preservesBusinessFailuresAndDefaultReason() {
		for (String reason : List.of("\"No relevant data\"", "null", "\" \"", "42")) {
			ChatResponse response = response("{\"is_success\":false,\"data\":null,\"reason\":" + reason + "}");
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response));
			var result = builder().build().apply(state(Map.of("input", "text"))).join();
			assertEquals(false, result.get("is_success"));
			assertFalse(result.containsKey("data"));
			assertEquals(reason.equals("\"No relevant data\"") ? "No relevant data" : "reason is empty",
					result.get("reason"));
			assertEquals(response.usage(), result.get(NodeOutput.TOKEN_USAGE_KEY));
		}
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response("{\"is_success\":false}")));
		assertEquals("reason is empty", builder().build().apply(state(Map.of("input", "text"))).join().get("reason"));
	}

	@Test
	void invalidInputDoesNotCallModel() {
		for (Map<String, Object> input : List.<Map<String, Object>>of(Map.of(), Map.of("input", " "),
				Map.of("input", 4))) {
			var result = builder().build().apply(state(input)).join();
			assertEquals(false, result.get("is_success"));
			assertFalse(result.containsKey("data"));
			assertTrue(result.get("reason").toString().contains("nonblank string"));
		}
		verifyNoInteractions(client);
	}

	@Test
	void invalidResultSchemaBecomesFailureRatherThanImplicitSuccess() {
		for (String json : List.of("invalid", "null", "{}", "{\"is_success\":\"true\"}", "{\"is_success\":true}",
				"{\"is_success\":true,\"data\":null}", "{\"is_success\":true,\"data\":[]}",
				"{\"is_success\":true,\"data\":{}} {}")) {
			when(client.call(any())).thenReturn(CompletableFuture.completedFuture(response(json)));
			var result = builder().build().apply(state(Map.of("input", "text"))).join();
			assertEquals(false, result.get("is_success"));
			assertNotNull(result.get("reason"));
			assertFalse(result.containsKey("data"));
		}
	}

	@Test
	void providerFailureWithoutMessageProducesStableReason() {
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
		assertEquals(Map.of("is_success", false, "reason", "IllegalStateException"),
				builder().build().apply(state(Map.of("input", "text"))).join());
	}

	@Test
	void cancellationIsNotConvertedIntoBusinessFailure() {
		CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
		when(client.call(any())).thenReturn(pending);
		var result = builder().build().apply(state(Map.of("input", "text")));
		assertFalse(result.isDone());
		assertTrue(result.cancel(true));
		assertTrue(pending.isCancelled());
		when(client.call(any())).thenReturn(CompletableFuture.failedFuture(new CancellationException("Cancelled")));
		assertThrows(CancellationException.class, () -> builder().build().apply(state(Map.of("input", "text"))).join());
	}

	@Test
	void validatesConfigurationAtBuildTime() {
		assertThrows(NullPointerException.class, () -> builder().chatClient(null).build());
		assertThrows(IllegalArgumentException.class, () -> builder().successKey(" ").build());
		assertThrows(IllegalArgumentException.class, () -> builder().dataKey("").build());
		assertThrows(IllegalArgumentException.class, () -> builder().reasonKey(null).build());
		assertThrows(IllegalArgumentException.class, () -> builder().dataKey("reason").build());
		assertThrows(IllegalArgumentException.class, () -> builder().reasonKey(NodeOutput.TOKEN_USAGE_KEY).build());
		assertThrows(IllegalArgumentException.class, () -> builder().parameters(List.of()).build());
		var param = ParameterParsingNode.param("a", "Boolean", "Flag");
		assertThrows(IllegalArgumentException.class, () -> builder().parameters(List.of(param, param)).build());
		assertThrows(IllegalArgumentException.class, () -> ParameterParsingNode.param(" ", "String", "Name"));
		assertThrows(IllegalArgumentException.class, () -> ParameterParsingNode.param("a", "Object", "Object"));
		assertThrows(NullPointerException.class, () -> ParameterParsingNode.param("a", "List", null));
		assertThrows(NullPointerException.class, () -> builder().instruction(null).build());
	}

}
