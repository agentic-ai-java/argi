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
package io.github.agentic.ai.graph.agent.hook.modelcalllimit;

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.ModelHook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook that tracks and limits model call counts.
 *
 * This hook monitors the number of model calls made during agent execution and can
 * terminate the agent when specified limits are reached. It supports both thread-level
 * and run-level call counting with configurable exit behaviors.
 */
@HookPositions({ HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL })
public class ModelCallLimitHook extends ModelHook {

	private static final String THREAD_COUNT_KEY = "__model_call_limit_thread_count__";

	private static final String RUN_COUNT_KEY = "__model_call_limit_run_count__";

	private final Integer threadLimit;

	private final Integer runLimit;

	private final ExitBehavior exitBehavior;

	private ModelCallLimitHook(Builder builder) {
		this.threadLimit = builder.threadLimit;
		this.runLimit = builder.runLimit;
		this.exitBehavior = builder.exitBehavior;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
		int threadModelCallCount = threadModelCallCount(state, config);
		int runModelCallCount = runModelCallCount(config);

		// Check if limits are already exceeded (before making the call)
		boolean threadLimitExceeded = threadLimit != null && threadModelCallCount >= threadLimit;
		boolean runLimitExceeded = runLimit != null && runModelCallCount >= runLimit;

		if (threadLimitExceeded || runLimitExceeded) {
			if (exitBehavior == ExitBehavior.ERROR) {
				throw new ModelCallLimitExceededException(threadModelCallCount, runModelCallCount, threadLimit,
						runLimit);
			}
			else if (exitBehavior == ExitBehavior.END) {
				// Add message indicating limit was exceeded and jump to end
				String message = buildLimitExceededMessage(threadModelCallCount, runModelCallCount, threadLimit,
						runLimit);

				List<ChatMessage> messages = new ArrayList<>();
				messages.add(ChatMessage.assistant(message));

				Map<String, Object> updates = new HashMap<>();
				updates.put("messages", messages);
				updates.put("jump_to", JumpTo.end);
				return CompletableFuture.completedFuture(updates);
			}
		}

		return CompletableFuture.completedFuture(Map.of());
	}

	@Override
	public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
		int threadModelCallCount = threadModelCallCount(state, config);
		int runModelCallCount = runModelCallCount(config);

		config.context().put(RUN_COUNT_KEY, runModelCallCount + 1);

		if (threadLimit == null) {
			return CompletableFuture.completedFuture(Map.of());
		}

		if (shouldPersistThreadCount(config)) {
			return CompletableFuture.completedFuture(Map.of(THREAD_COUNT_KEY, threadModelCallCount + 1));
		}

		config.context().put(THREAD_COUNT_KEY, threadModelCallCount + 1);
		return CompletableFuture.completedFuture(Map.of());
	}

	private int threadModelCallCount(OverAllState state, RunnableConfig config) {
		if (threadLimit == null) {
			return 0;
		}
		if (shouldPersistThreadCount(config)) {
			return countFrom(state.value(THREAD_COUNT_KEY).orElse(0));
		}
		return countFrom(config.context().getOrDefault(THREAD_COUNT_KEY, 0));
	}

	private int runModelCallCount(RunnableConfig config) {
		return countFrom(config.context().getOrDefault(RUN_COUNT_KEY, 0));
	}

	private boolean shouldPersistThreadCount(RunnableConfig config) {
		return config.threadId().isPresent();
	}

	private int countFrom(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		return 0;
	}

	private String buildLimitExceededMessage(int threadCount, int runCount, Integer threadLimit, Integer runLimit) {
		List<String> exceededLimits = new ArrayList<>();
		if (threadLimit != null && threadCount >= threadLimit) {
			exceededLimits.add(String.format("thread limit (%d/%d)", threadCount, threadLimit));
		}
		if (runLimit != null && runCount >= runLimit) {
			exceededLimits.add(String.format("run limit (%d/%d)", runCount, runLimit));
		}
		return "Model call limits exceeded: " + String.join(", ", exceededLimits);
	}

	@Override
	public String getName() {
		return "ModelCallLimit";
	}

	@Override
	public List<JumpTo> canJumpTo() {
		if (exitBehavior == ExitBehavior.END) {
			return List.of(JumpTo.end);
		}
		return List.of();
	}

	@Override
	public Map<String, KeyStrategy> getKeyStrategys() {
		if (threadLimit == null) {
			return Map.of();
		}
		return Map.of(THREAD_COUNT_KEY, KeyStrategy.REPLACE);
	}

	public enum ExitBehavior {

		END, ERROR

	}

	public static class Builder {

		private Integer threadLimit;

		private Integer runLimit;

		private ExitBehavior exitBehavior = ExitBehavior.END;

		public Builder threadLimit(Integer threadLimit) {
			this.threadLimit = threadLimit;
			return this;
		}

		public Builder runLimit(Integer runLimit) {
			this.runLimit = runLimit;
			return this;
		}

		public Builder exitBehavior(ExitBehavior exitBehavior) {
			this.exitBehavior = exitBehavior;
			return this;
		}

		public ModelCallLimitHook build() {
			if (threadLimit == null && runLimit == null) {
				throw new IllegalArgumentException("At least one limit must be specified (threadLimit or runLimit)");
			}
			return new ModelCallLimitHook(this);
		}

	}

}
