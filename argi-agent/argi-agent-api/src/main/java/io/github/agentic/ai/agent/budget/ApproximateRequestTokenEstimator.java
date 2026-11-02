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
package io.github.agentic.ai.agent.budget;

import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The default estimator: a character based approximation of the token consumption of
 * a request.
 *
 * <p>
 * Unlike the message-only counters of the framework, the estimate covers the whole
 * request which is finally dispatched: the system message, the conversation, the
 * disclosed tool schemas and the reserved output.
 *
 * <p>
 * The tool schemas are counted for the bindings the request actually discloses. When
 * the request carries an explicit tool selection, only the selected bindings are
 * counted; the dynamic bindings of the request are always counted because they are
 * disclosed next to the selected ones.
 */
public final class ApproximateRequestTokenEstimator implements RequestTokenEstimator {

	/**
	 * The average number of characters per token used by the approximation.
	 */
	public static final int DEFAULT_CHARS_PER_TOKEN = 4;

	private final int charsPerToken;

	/**
	 * Creates an estimator with the default character ratio.
	 */
	public ApproximateRequestTokenEstimator() {
		this(DEFAULT_CHARS_PER_TOKEN);
	}

	/**
	 * Creates an estimator with a custom character ratio.
	 * @param charsPerToken the average number of characters per token, must be positive
	 */
	public ApproximateRequestTokenEstimator(int charsPerToken) {
		if (charsPerToken <= 0) {
			throw new IllegalArgumentException("charsPerToken must be positive");
		}
		this.charsPerToken = charsPerToken;
	}

	@Override
	public RequestTokenEstimate estimate(ModelRequest request, ContextWindowBudget budget) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(budget, "budget must not be null");
		return new RequestTokenEstimate(systemTokens(request), messageTokens(request), toolSchemaTokens(request),
				budget.outputReserve(), EstimationAccuracy.APPROXIMATE);
	}

	private int systemTokens(ModelRequest request) {
		ChatMessage systemMessage = request.getSystemMessage();
		return systemMessage == null ? 0 : messageTokens(systemMessage);
	}

	private int messageTokens(ModelRequest request) {
		int total = 0;
		for (ChatMessage message : request.getMessages()) {
			total += messageTokens(message);
		}
		return total;
	}

	private int messageTokens(ChatMessage message) {
		int total = 0;
		for (ContentPart part : message.content()) {
			if (part.text() != null) {
				total += tokens(part.text());
			}
			else if (part.data() != null) {
				total += tokens(part.data().length);
			}
		}
		for (ToolCall call : message.toolCalls()) {
			total += tokens(call.arguments());
		}
		return total;
	}

	private int toolSchemaTokens(ModelRequest request) {
		Set<String> selection = request.hasToolSelection() ? new HashSet<>(request.getTools()) : null;
		int total = 0;
		for (ToolDefinition definition : request.getToolRegistry().definitions()) {
			if (selection == null || selection.contains(definition.name())) {
				total += definitionTokens(definition);
			}
		}
		for (ToolDefinition definition : request.getDynamicToolRegistry().definitions()) {
			total += definitionTokens(definition);
		}
		for (Map.Entry<String, String> description : request.getToolDescriptions().entrySet()) {
			total += tokens(description.getKey()) + tokens(description.getValue());
		}
		return total;
	}

	private int definitionTokens(ToolDefinition definition) {
		return tokens(definition.name()) + tokens(definition.description()) + tokens(definition.inputSchema());
	}

	private int tokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return tokens(text.length());
	}

	private int tokens(int length) {
		if (length <= 0) {
			return 0;
		}
		return (length + charsPerToken - 1) / charsPerToken;
	}

}
