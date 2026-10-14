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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.AsyncNodeAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/**
 * Extracts parameters through a single neutral model turn, preserving business failure
 * results.
 */
public final class ParameterParsingNode implements AsyncNodeAction {

	private static final String SYSTEM_PROMPT = """
			### Task
			Extract parameter values from user input.
			Input text: {inputText}
			Instruction: {instruction}
			Parameters (name, type, description): {parameters}
			### Output
			Return only a valid JSON object with exactly three keys:
			"is_success": a boolean indicating successful extraction,
			"data": an object containing every defined parameter (null for missing values),
			"reason": "success" when successful, otherwise a brief explanation.
			If the input is irrelevant or all parameters are missing, return is_success false and data null.
			Parameter types can be String, Number, Boolean, or List. Do not return markdown or a preamble.
			""";

	private static final List<ChatMessage> EXAMPLES = List.of(ChatMessage.user("""
			{"input_text":"Paper number: 2405.10739", "parameters":[{"name":"paper_num","type":"String"}]}
			"""), ChatMessage.assistant("""
			{"is_success":true,"data":{"paper_num":"2405.10739"},"reason":"success"}
			"""), ChatMessage.user("""
			{"input_text":null,"parameters":[{"name":"outlines","type":"List"}]}
			"""), ChatMessage.assistant("""
			{"is_success":false,"data":null,"reason":"Input text is empty"}
			"""));

	private final ChatClient chatClient;

	private final String inputText;

	private final String inputTextKey;

	private final List<Param> parameters;

	private final String successKey;

	private final String dataKey;

	private final String reasonKey;

	private final String instruction;

	public ParameterParsingNode(ChatClient chatClient, String inputText, String inputTextKey, List<Param> parameters,
			String instruction, String successKey, String dataKey, String reasonKey) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		this.successKey = StructuredChatSupport.requireKey(successKey, "successKey");
		this.dataKey = StructuredChatSupport.requireKey(dataKey, "dataKey");
		this.reasonKey = StructuredChatSupport.requireKey(reasonKey, "reasonKey");
		List<String> keys = List.of(successKey, dataKey, reasonKey);
		if (keys.stream().distinct().count() != keys.size() || keys.contains(NodeOutput.TOKEN_USAGE_KEY)) {
			throw new IllegalArgumentException(
					"Result keys must be distinct and must not overwrite token usage metadata");
		}
		this.inputText = inputText;
		this.inputTextKey = inputTextKey;
		this.parameters = List.copyOf(parameters);
		if (this.parameters.isEmpty()
				|| this.parameters.stream().map(Param::name).distinct().count() != this.parameters.size()) {
			throw new IllegalArgumentException("Parameters must not be empty and names must be unique");
		}
		this.instruction = Objects.requireNonNull(instruction, "instruction must not be null");
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		return Mono.defer(() -> {
			Object input = inputTextKey == null || inputTextKey.isBlank() ? inputText
					: state.value(inputTextKey).orElse(inputText);
			if (!(input instanceof String currentInput) || currentInput.isBlank()) {
				throw new IllegalArgumentException("Input text must be a nonblank string");
			}
			String system = StructuredChatSupport.render(SYSTEM_PROMPT,
					Map.of("inputText", currentInput, "instruction", StructuredChatSupport.render(state, instruction),
							"parameters", StructuredChatSupport.JSON.valueToTree(parameters).toString()));
			List<ChatMessage> messages = new ArrayList<>();
			messages.add(ChatMessage.system(system));
			messages.addAll(EXAMPLES);
			messages.add(ChatMessage.user(currentInput));
			return StructuredChatSupport.call(chatClient, messages).map(this::result);
		})
			.onErrorResume(error -> !(error instanceof CancellationException),
					error -> Mono.just(Map.<String, Object>of(successKey, false, reasonKey,
							error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())))
			.toFuture();
	}

	private Map<String, Object> result(ChatResponse response) {
		JsonNode json = StructuredChatSupport.object(response.text());
		JsonNode success = json.get("is_success");
		if (success == null || !success.isBoolean()) {
			throw new IllegalArgumentException("ChatClient response must contain a boolean is_success");
		}
		Map<String, Object> result = new HashMap<>();
		result.put(successKey, success.booleanValue());
		result.put(NodeOutput.TOKEN_USAGE_KEY, response.usage());
		if (success.booleanValue()) {
			JsonNode data = json.get("data");
			if (data == null || !data.isObject()) {
				throw new IllegalArgumentException("ChatClient successful response must contain an object data");
			}
			result.put(dataKey, StructuredChatSupport.JSON.convertValue(data, new TypeReference<Map<String, Object>>() {
			}));
			result.put(reasonKey, "success");
		}
		else {
			JsonNode reason = json.get("reason");
			result.put(reasonKey, reason != null && reason.isTextual() && !reason.textValue().isBlank()
					? reason.textValue() : "reason is empty");
		}
		return result;
	}

	public record Param(String name, String type, String description) {
		public Param {
			StructuredChatSupport.requireKey(name, "Parameter name");
			if (!List.of("String", "Number", "Boolean", "List").contains(type)) {
				throw new IllegalArgumentException("Unsupported parameter type: " + type);
			}
			Objects.requireNonNull(description, "description must not be null");
		}
	}

	public static Param param(String name, String type, String description) {
		return new Param(name, type, description);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String inputText = "";

		private String inputTextKey;

		private ChatClient chatClient;

		private List<Param> parameters = List.of();

		private String successKey = "is_success";

		private String dataKey = "data";

		private String reasonKey = "reason";

		private String instruction = "";

		public Builder inputText(String inputText) {
			this.inputText = inputText;
			return this;
		}

		public Builder inputTextKey(String key) {
			this.inputTextKey = key;
			return this;
		}

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder parameters(List<Param> parameters) {
			this.parameters = parameters;
			return this;
		}

		public Builder successKey(String key) {
			this.successKey = key;
			return this;
		}

		public Builder dataKey(String key) {
			this.dataKey = key;
			return this;
		}

		public Builder reasonKey(String key) {
			this.reasonKey = key;
			return this;
		}

		public Builder instruction(String instruction) {
			this.instruction = instruction;
			return this;
		}

		public ParameterParsingNode build() {
			return new ParameterParsingNode(chatClient, inputText, inputTextKey, parameters, instruction, successKey,
					dataKey, reasonKey);
		}

	}

}
