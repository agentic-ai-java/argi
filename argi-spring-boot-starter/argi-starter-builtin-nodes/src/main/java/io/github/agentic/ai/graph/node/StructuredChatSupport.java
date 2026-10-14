/*
 * Copyright 2025-2026 the original author or authors.
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.graph.OverAllState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.ErrorBuffer;
import reactor.core.publisher.Mono;

/** Shared single-turn validation and template rendering for structured chat nodes. */
final class StructuredChatSupport {

	static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");

	private StructuredChatSupport() {
	}

	static String render(OverAllState state, String text) {
		Map<String, Object> values = new HashMap<>();
		Matcher matcher = VARIABLE.matcher(text);
		while (matcher.find()) {
			values.put(matcher.group(1), state.value(matcher.group(1)).orElse(""));
		}
		return render(text, values);
	}

	static String render(String text, Map<String, ?> values) {
		ErrorBuffer errors = new ErrorBuffer();
		STGroup group = new STGroup('{', '}');
		group.setListener(errors);
		ST template;
		try {
			template = new ST(group, text);
		}
		catch (STException ex) {
			throw new IllegalArgumentException("Invalid prompt template: " + errors, ex);
		}
		values.forEach(template::add);
		String result = template.render();
		if (!errors.errors.isEmpty()) {
			throw new IllegalArgumentException("Invalid prompt template: " + errors);
		}
		return result;
	}

	static Mono<ChatResponse> call(ChatClient client, List<ChatMessage> messages) {
		return Mono.defer(() -> Mono.fromCompletionStage(client.call(ChatRequest.of(messages))))
			.switchIfEmpty(Mono.error(new IllegalStateException("ChatClient returned no response")))
			.map(response -> {
				if (response.messages().isEmpty() || !response.toolCalls().isEmpty()
						|| response.messages()
							.stream()
							.anyMatch(message -> message.role() != ChatRole.ASSISTANT || !message.toolCalls().isEmpty())
						|| response.text().isBlank()) {
					throw new IllegalStateException("ChatClient must return assistant text without tool calls");
				}
				return response;
			});
	}

	static JsonNode object(String text) {
		String json = text.strip();
		// Strip only an enclosing fence; embedded backticks are part of the JSON data.
		if (json.startsWith("```json\n") || json.startsWith("```\n") || json.startsWith("```json\r\n")
				|| json.startsWith("```\r\n")) {
			if (!json.endsWith("```")) {
				throw new IllegalArgumentException("ChatClient returned an unclosed JSON fence");
			}
			json = json.substring(json.indexOf('\n') + 1, json.length() - 3).strip();
		}
		try {
			JsonNode result = JSON.readTree(json);
			if (result == null || !result.isObject()) {
				throw new IllegalArgumentException("ChatClient response must be a JSON object");
			}
			return result;
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("ChatClient returned invalid JSON", ex);
		}
	}

	static String requireKey(String key, String name) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return key;
	}

}
