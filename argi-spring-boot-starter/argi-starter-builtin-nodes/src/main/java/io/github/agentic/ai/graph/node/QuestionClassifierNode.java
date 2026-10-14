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

import com.fasterxml.jackson.databind.JsonNode;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.AsyncNodeAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/** Classifies each input independently through one framework-neutral model turn. */
public final class QuestionClassifierNode implements AsyncNodeAction {

	private static final String SYSTEM_PROMPT = """
			### Task
			Assign exactly one category to the input text and extract related keywords.
			Input text: {inputText}
			Categories: {categories}
			Classification instructions: {classificationInstructions}
			### Output
			Return only a valid JSON object with keys "keywords" (an array of strings)
			and "category_name" (exactly one category from the provided list).
			""";

	private static final List<ChatMessage> EXAMPLES = List.of(ChatMessage.user("""
			{"input_text":"Prompt service and friendly staff", "categories":["Customer Service","Product"]}
			"""), ChatMessage.assistant("""
			{"keywords":["prompt service","friendly staff"],"category_name":"Customer Service"}
			"""), ChatMessage.user("""
			{"input_text":"Slow to bring the food", "categories":["Food Quality","Experience","Price"]}
			"""), ChatMessage.assistant("""
			{"keywords":["slow","food"],"category_name":"Experience"}
			"""));

	private final ChatClient chatClient;

	private final Map<String, String> categories;

	private final List<String> classificationInstructions;

	private final String inputTextKey;

	private final String outputKey;

	public QuestionClassifierNode(ChatClient chatClient, String inputTextKey, Map<String, String> categories,
			List<String> classificationInstructions, String outputKey) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		this.inputTextKey = StructuredChatSupport.requireKey(inputTextKey, "inputTextKey");
		this.outputKey = StructuredChatSupport.requireKey(outputKey, "outputKey");
		if (NodeOutput.TOKEN_USAGE_KEY.equals(outputKey) || "messages".equals(outputKey)) {
			throw new IllegalArgumentException("outputKey must not overwrite messages or token usage metadata");
		}
		this.categories = Map.copyOf(categories);
		if (this.categories.isEmpty() || this.categories.entrySet()
			.stream()
			.anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
			throw new IllegalArgumentException("Category IDs and names must not be empty");
		}
		this.classificationInstructions = List.copyOf(classificationInstructions);
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		return Mono.defer(() -> {
			Object input = state.value(inputTextKey).orElse(null);
			if (!(input instanceof String inputText) || inputText.isBlank()) {
				throw new IllegalArgumentException("Input text must be a nonblank string");
			}
			Map<String, String> renderedCategories = new LinkedHashMap<>();
			categories
				.forEach((id, template) -> renderedCategories.put(id, StructuredChatSupport.render(state, template)));
			if (renderedCategories.values().stream().anyMatch(String::isBlank)
					|| renderedCategories.values().stream().distinct().count() != renderedCategories.size()) {
				throw new IllegalArgumentException("Rendered category names must be nonblank and unique");
			}
			List<String> instructions = classificationInstructions.stream()
				.map(template -> StructuredChatSupport.render(state, template))
				.toList();
			String system = StructuredChatSupport.render(SYSTEM_PROMPT, Map.of("inputText", inputText, "categories",
					StructuredChatSupport.JSON.valueToTree(renderedCategories.values()).toString(),
					"classificationInstructions", StructuredChatSupport.JSON.valueToTree(instructions).toString()));
			List<ChatMessage> messages = new ArrayList<>();
			messages.add(ChatMessage.system(system));
			messages.addAll(EXAMPLES);
			messages.add(ChatMessage.user(inputText));
			return StructuredChatSupport.call(chatClient, messages)
				.map(response -> result(state, renderedCategories, response));
		}).toFuture();
	}

	private Map<String, Object> result(OverAllState state, Map<String, String> renderedCategories,
			ChatResponse response) {
		JsonNode category = StructuredChatSupport.object(response.text()).get("category_name");
		if (category == null || !category.isTextual()) {
			throw new IllegalArgumentException("ChatClient response must contain a textual category_name");
		}
		String id = renderedCategories.entrySet()
			.stream()
			.filter(entry -> entry.getValue().equals(category.textValue()))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElseThrow(
					() -> new IllegalArgumentException("ChatClient returned a category outside the configured list"));
		Map<String, Object> result = new HashMap<>();
		result.put(outputKey, id);
		result.put(NodeOutput.TOKEN_USAGE_KEY, response.usage());
		if (state.value("messages").isPresent()) {
			result.put("messages", response.messages());
		}
		return result;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String inputTextKey;

		private ChatClient chatClient;

		private Map<String, String> categories = Map.of();

		private List<String> classificationInstructions = List.of();

		private String outputKey;

		public Builder inputTextKey(String key) {
			this.inputTextKey = key;
			return this;
		}

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		/** Maps category IDs to names. Names may reference state values using {name}. */
		public Builder categories(Map<String, String> categories) {
			this.categories = categories;
			return this;
		}

		/** Instructions may reference state values using {name}. */
		public Builder classificationInstructions(List<String> instructions) {
			this.classificationInstructions = instructions;
			return this;
		}

		public Builder outputKey(String key) {
			this.outputKey = key;
			return this;
		}

		public QuestionClassifierNode build() {
			return new QuestionClassifierNode(chatClient, inputTextKey, categories, classificationInstructions,
					outputKey);
		}

	}

}
