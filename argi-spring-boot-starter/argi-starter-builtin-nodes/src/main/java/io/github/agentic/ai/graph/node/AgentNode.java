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
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
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
import java.util.concurrent.CompletionException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * A bounded, asynchronous ARGI-owned model/tool loop within one graph node. Adapters only
 * generate responses; this node owns tool execution. State is submitted only after the
 * whole loop succeeds. Native framework agents are not used here.
 */
public final class AgentNode implements AsyncNodeAction {

	private final ChatClient chatClient;

	private final ToolRegistry toolRegistry;

	private final Strategy strategy;

	private final String systemPrompt;

	private final String userPrompt;

	private final GenerationOptions options;

	private final int maxModelTurns;

	private final int maxRetries;

	private final String outputKey;

	public enum Strategy {

		/**
		 * Feed tool results back to the model until it answers or all tools return
		 * directly.
		 */
		REACT,
		/**
		 * Execute the first model turn's tools and return their results without another
		 * turn.
		 */
		TOOL_CALLING

	}

	private AgentNode(Builder builder) {
		this.chatClient = Objects.requireNonNull(builder.chatClient, "chatClient must not be null");
		this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry must not be null");
		this.strategy = Objects.requireNonNull(builder.strategy, "strategy must not be null");
		this.systemPrompt = Objects.requireNonNull(builder.systemPrompt, "systemPrompt must not be null");
		this.userPrompt = Objects.requireNonNull(builder.userPrompt, "userPrompt must not be null");
		this.options = Objects.requireNonNull(builder.options, "options must not be null");
		this.maxModelTurns = builder.maxModelTurns;
		this.maxRetries = builder.maxRetries;
		if (maxModelTurns < 1 || maxRetries < 0) {
			throw new IllegalArgumentException("maxModelTurns must be positive and maxRetries must not be negative");
		}
		this.outputKey = StructuredChatSupport.requireKey(builder.outputKey, "outputKey");
		if (List.of("messages", LlmNode.LLM_RESPONSE_KEY, NodeOutput.TOKEN_USAGE_KEY).contains(outputKey)) {
			throw new IllegalArgumentException("outputKey must not overwrite messages, model response or usage");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		return Mono.defer(() -> {
			Run run = new Run(Objects.requireNonNull(state, "state must not be null"));
			// expand uses an iterative drain, including when model/tool stages complete
			// synchronously.
			return run.turn()
				.expand(finished -> finished ? Mono.empty() : run.turn())
				.last()
				.map(ignored -> run.output());
		}).toFuture();
	}

	private final class Run {

		private final List<ChatMessage> history = new ArrayList<>();

		private final List<ChatMessage> generated = new ArrayList<>();

		private final ToolContext context;

		private int turns;

		private Usage usage;

		private ChatResponse lastResponse;

		private String answer;

		private Run(OverAllState state) {
			context = new ToolContext(Map.of("state", state));
			if (!systemPrompt.isBlank()) {
				history.add(ChatMessage.system(StructuredChatSupport.render(state, systemPrompt)));
			}
			Object messages = state.value("messages").orElse(List.of());
			if (!(messages instanceof List<?> list)) {
				throw new IllegalArgumentException("History must be a list of ARGI ChatMessage values");
			}
			for (Object item : list) {
				if (!(item instanceof ChatMessage message)) {
					throw new IllegalArgumentException("History must contain only ARGI ChatMessage values");
				}
				history.add(message);
			}
			if (!userPrompt.isBlank()) {
				ChatMessage prompt = ChatMessage.user(StructuredChatSupport.render(state, userPrompt));
				history.add(prompt);
				generated.add(prompt);
			}
		}

		private Mono<Boolean> turn() {
			return Mono.defer(() -> {
				if (turns >= maxModelTurns) {
					return Mono.error(new IllegalStateException("AgentNode exceeded maxModelTurns=" + maxModelTurns));
				}
				turns++;
				ChatRequest request = new ChatRequest(history, toolRegistry.definitions(), options, Map.of());
				// Retry this immutable model request only, never a loop containing tool
				// side effects.
				return Mono.defer(() -> Mono.fromCompletionStage(chatClient.call(request)))
					.onErrorMap(AgentNode::unwrap)
					.retryWhen(Retry.max(maxRetries)
						.filter(AgentNode::retryable)
						.onRetryExhaustedThrow((spec, signal) -> signal.failure()))
					.switchIfEmpty(Mono.error(new IllegalStateException("ChatClient returned no response")))
					.flatMap(this::accept);
			});
		}

		private Mono<Boolean> accept(ChatResponse response) {
			if (response.messages().size() != 1 || response.messages().get(0).role() != ChatRole.ASSISTANT) {
				return Mono
					.error(new IllegalStateException("AgentNode requires exactly one assistant message per turn"));
			}
			ChatMessage message = response.messages().get(0);
			List<ToolCall> calls = response.toolCalls().isEmpty() ? message.toolCalls() : response.toolCalls();
			if (!message.toolCalls().isEmpty() && !message.toolCalls().equals(calls)) {
				return Mono.error(new IllegalStateException("Response and assistant message tool calls disagree"));
			}
			if (calls.stream().map(ToolCall::id).distinct().count() != calls.size()) {
				return Mono.error(new IllegalStateException("Tool call IDs must be unique within a turn"));
			}
			// Validate every binding before the first tool can produce a side effect.
			for (ToolCall call : calls) {
				if (toolRegistry.find(call.name()).isEmpty()) {
					return Mono.error(new IllegalArgumentException("Unknown tool: " + call.name()));
				}
			}
			if (!message.toolCalls().equals(calls)) {
				message = new ChatMessage(message.role(), message.content(), calls, null, null, message.metadata());
			}
			generated.add(message);
			history.add(message);
			lastResponse = response;
			usage = usage == null ? response.usage()
					: new Usage(sum(usage.inputTokens(), response.usage().inputTokens()),
							sum(usage.outputTokens(), response.usage().outputTokens()),
							sum(usage.totalTokens(), response.usage().totalTokens()));
			if (calls.isEmpty()) {
				answer = response.text();
				return Mono.just(true);
			}
			boolean returnDirect = strategy == Strategy.TOOL_CALLING
					|| calls.stream().allMatch(call -> toolRegistry.find(call.name()).orElseThrow().returnDirect());
			return Flux.fromIterable(calls)
				.concatMap(call -> Mono.fromCompletionStage(() -> toolRegistry.execute(call, context))
					.map(result -> result.toMessage(call)), 1)
				.collectList()
				.map(results -> {
					history.addAll(results);
					generated.addAll(results);
					if (returnDirect) {
						answer = results.stream().map(ChatMessage::text).reduce("", String::concat);
					}
					return returnDirect;
				});
		}

		private Map<String, Object> output() {
			Map<String, Object> update = new HashMap<>();
			update.put(outputKey, answer);
			update.put("messages", List.copyOf(generated));
			update.put(LlmNode.LLM_RESPONSE_KEY, lastResponse);
			update.put(NodeOutput.TOKEN_USAGE_KEY, usage);
			return update;
		}

	}

	private static Long sum(Long left, Long right) {
		return left == null || right == null ? null : Math.addExact(left, right);
	}

	private static Throwable unwrap(Throwable error) {
		while (error instanceof CompletionException && error.getCause() != null) {
			error = error.getCause();
		}
		return error;
	}

	private static boolean retryable(Throwable error) {
		return !(error instanceof CancellationException || error instanceof IllegalArgumentException
				|| error instanceof UnsupportedOperationException);
	}

	public static final class Builder {

		private ChatClient chatClient;

		private ToolRegistry toolRegistry = new ToolRegistry(List.of());

		private Strategy strategy = Strategy.REACT;

		private String systemPrompt = "";

		private String userPrompt = "";

		private GenerationOptions options = GenerationOptions.defaults();

		private int maxModelTurns = 10;

		private int maxRetries;

		private String outputKey = "agent_output";

		private Builder() {
		}

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder toolRegistry(ToolRegistry toolRegistry) {
			this.toolRegistry = toolRegistry;
			return this;
		}

		public Builder strategy(Strategy strategy) {
			this.strategy = strategy;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder userPrompt(String userPrompt) {
			this.userPrompt = userPrompt;
			return this;
		}

		public Builder options(GenerationOptions options) {
			this.options = options;
			return this;
		}

		/**
		 * Maximum model turns, each allowing up to maxRetries additional model-only
		 * attempts.
		 */
		public Builder maxModelTurns(int maxModelTurns) {
			this.maxModelTurns = maxModelTurns;
			return this;
		}

		/**
		 * Additional attempts per model turn; defaults to zero. Tools are never retried.
		 */
		public Builder maxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
			return this;
		}

		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		public AgentNode build() {
			return new AgentNode(this);
		}

	}

}
