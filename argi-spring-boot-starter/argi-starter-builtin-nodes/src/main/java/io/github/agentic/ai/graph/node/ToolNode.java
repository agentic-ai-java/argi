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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.AsyncNodeAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Executes declared ARGI tools sequentially, preserving invocation identity and result
 * order.
 */
public final class ToolNode implements AsyncNodeAction {

	private final ToolRegistry toolRegistry;

	private final String llmResponseKey;

	private final String outputKey;

	private ToolNode(Builder builder) {
		this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry must not be null");
		this.llmResponseKey = Objects.requireNonNull(builder.llmResponseKey, "llmResponseKey must not be null");
		if (llmResponseKey.isBlank()) {
			throw new IllegalArgumentException("llmResponseKey must not be blank");
		}
		this.outputKey = builder.outputKey;
		if (llmResponseKey.equals(outputKey)) {
			throw new IllegalArgumentException("outputKey must not overwrite the model response");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		return Mono.defer(() -> {
			List<ToolCall> calls = calls(state);
			ToolContext context = new ToolContext(Map.of("state", state));
			// concatMap keeps side effects ordered and cancels the active tool on
			// cancellation.
			return Flux.fromIterable(calls)
				.concatMap(call -> Mono.fromCompletionStage(() -> toolRegistry.execute(call, context))
					.map(result -> result.toMessage(call)), 1)
				.collectList()
				.map(messages -> {
					List<ChatMessage> results = List.copyOf(messages);
					Map<String, Object> update = new HashMap<>();
					update.put("messages", results);
					if (outputKey != null && !outputKey.isBlank()) {
						update.put(outputKey, results);
					}
					return update;
				});
		}).toFuture();
	}

	private List<ToolCall> calls(OverAllState state) {
		Objects.requireNonNull(state, "state must not be null");
		Object value = state.value(llmResponseKey)
			.orElseGet(() -> state.value("messages")
				.orElseThrow(() -> new IllegalArgumentException("No model response or message history in state")));
		if (value instanceof ChatResponse response) {
			if (!response.toolCalls().isEmpty()) {
				return response.toolCalls();
			}
			return response.messages().stream().flatMap(message -> message.toolCalls().stream()).toList();
		}
		if (value instanceof List<?> history && !history.isEmpty()) {
			value = history.get(history.size() - 1);
		}
		if (value instanceof ChatMessage message && message.role() == ChatRole.ASSISTANT) {
			return message.toolCalls();
		}
		throw new IllegalArgumentException("ToolNode requires an ARGI ChatResponse or a final assistant message");
	}

	public static final class Builder {

		private ToolRegistry toolRegistry;

		private String llmResponseKey = LlmNode.LLM_RESPONSE_KEY;

		private String outputKey;

		private Builder() {
		}

		public Builder toolRegistry(ToolRegistry toolRegistry) {
			this.toolRegistry = toolRegistry;
			return this;
		}

		public Builder llmResponseKey(String key) {
			this.llmResponseKey = key;
			return this;
		}

		public Builder outputKey(String key) {
			this.outputKey = key;
			return this;
		}

		public ToolNode build() {
			return new ToolNode(this);
		}

	}

}
