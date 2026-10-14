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
package io.github.agentic.ai.graph.agent.hook.toolcalllimit;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.ModelHook;
import io.github.agentic.ai.api.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook that tracks and limits tool call counts.
 *
 * This hook monitors the number of tool calls made during agent execution and can
 * terminate the agent when specified limits are reached. A batch that would exceed a
 * limit is rejected in full before any of its tools execute. Thread counts are
 * checkpointed when an explicit thread ID is provided; run counts remain
 * invocation-local.
 */
@HookPositions({ HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL })
public class ToolCallLimitHook extends ModelHook {

	private static final String THREAD_COUNT_KEY_PREFIX = "__tool_call_limit_thread_count__";

	private static final String RUN_COUNT_KEY_PREFIX = "__tool_call_limit_run_count__";

	private static final String PENDING_ERROR_KEY_PREFIX = "__tool_call_limit_pending_error__";

	private final String toolName; // null means track all tools

	private final Integer threadLimit;

	private final Integer runLimit;

	private final ExitBehavior exitBehavior;

	private ToolCallLimitHook(Builder builder) {
		this.toolName = builder.toolName;
		this.threadLimit = builder.threadLimit;
		this.runLimit = builder.runLimit;
		this.exitBehavior = builder.exitBehavior;
	}

	public static Builder builder() {
		return new Builder();
	}

	private String getThreadCountKey() {
		String trackKey = toolName != null ? toolName : "__all__";
		return THREAD_COUNT_KEY_PREFIX + "_" + trackKey;
	}

	private String getRunCountKey() {
		String trackKey = toolName != null ? toolName : "__all__";
		return RUN_COUNT_KEY_PREFIX + "_" + trackKey;
	}

	@Override
	public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
		Object pendingError = config.context().remove(getPendingErrorKey());
		if (pendingError instanceof ToolCallLimitExceededException exception) {
			throw exception;
		}
		int threadCount = threadCallCount(state, config);
		int runCount = countFrom(config.context().get(getRunCountKey()));

		boolean threadLimitExceeded = threadLimit != null && threadCount >= threadLimit;
		boolean runLimitExceeded = runLimit != null && runCount >= runLimit;

		if (threadLimitExceeded || runLimitExceeded) {
			if (exitBehavior == ExitBehavior.ERROR) {
				throw new ToolCallLimitExceededException(threadCount, runCount, threadLimit, runLimit, toolName);
			}
			else if (exitBehavior == ExitBehavior.END) {
				String message = buildLimitExceededMessage(threadCount, runCount, threadLimit, runLimit, toolName);

				// Do not copy old messages
				List<ChatMessage> messages = new ArrayList<>();
				// This new message will be appended to the messages list
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
		// Count new tool calls from the latest AI message
		List<ChatMessage> messages = (List<ChatMessage>) state.value("messages").orElse(List.of());
		if (messages.isEmpty()) {
			return CompletableFuture.completedFuture(Map.of());
		}

		// Get the last message (should be an ChatMessage with tool calls)
		ChatMessage lastMessage = messages.get(messages.size() - 1);
		int newCalls = 0;

		if (lastMessage instanceof ChatMessage && ((ChatMessage) lastMessage).role() == ChatRole.ASSISTANT) {
			ChatMessage aiMessage = (ChatMessage) lastMessage;
			if (aiMessage.toolCalls() != null) {
				if (toolName == null) {
					// Count all tool calls
					newCalls = aiMessage.toolCalls().size();
				}
				else {
					// Count only calls to the specified tool
					for (io.github.agentic.ai.api.chat.ToolCall toolCall : aiMessage.toolCalls()) {
						if (toolName.equals(toolCall.name())) {
							newCalls++;
						}
					}
				}
			}
		}

		if (newCalls > 0) {
			int threadCount = threadCallCount(state, config);
			int runCount = countFrom(config.context().get(getRunCountKey()));
			if ((threadLimit != null && (long) threadCount + newCalls > threadLimit)
					|| (runLimit != null && (long) runCount + newCalls > runLimit)) {
				return rejectBatch((ChatMessage) lastMessage, config, threadCount + newCalls, runCount + newCalls);
			}

			config.context().put(getRunCountKey(), runCount + newCalls);
			if (threadLimit != null) {
				if (config.threadId().isPresent()) {
					return CompletableFuture.completedFuture(Map.of(getThreadCountKey(), threadCount + newCalls));
				}
				config.context().put(getThreadCountKey(), threadCount + newCalls);
			}
		}

		return CompletableFuture.completedFuture(Map.of());
	}

	private CompletableFuture<Map<String, Object>> rejectBatch(ChatMessage message, RunnableConfig config,
			int threadCount, int runCount) {
		String reason = buildLimitExceededMessage(threadCount, runCount, threadLimit, runLimit, toolName);
		List<ChatMessage> responses = message.toolCalls()
			.stream()
			.map(call -> ToolResult.error(reason).toMessage(call))
			.toList();
		List<ChatMessage> messages = new ArrayList<>();
		messages.addAll(responses);
		Map<String, Object> updates = new HashMap<>();
		updates.put("messages", messages);
		if (exitBehavior == ExitBehavior.ERROR) {
			// Checkpoint paired responses before raising the error in the next
			// before-model hook.
			config.context()
				.put(getPendingErrorKey(),
						new ToolCallLimitExceededException(threadCount, runCount, threadLimit, runLimit, toolName));
			updates.put("jump_to", JumpTo.model);
		}
		else {
			messages.add(ChatMessage.assistant(reason));
			updates.put("jump_to", JumpTo.end);
		}
		return CompletableFuture.completedFuture(updates);
	}

	private String getPendingErrorKey() {
		return PENDING_ERROR_KEY_PREFIX + "_" + (toolName != null ? toolName : "__all__");
	}

	private int threadCallCount(OverAllState state, RunnableConfig config) {
		if (threadLimit == null) {
			return 0;
		}
		return countFrom(config.threadId().isPresent() ? state.value(getThreadCountKey()).orElse(0)
				: config.context().get(getThreadCountKey()));
	}

	private int countFrom(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private String buildLimitExceededMessage(int threadCount, int runCount, Integer threadLimit, Integer runLimit,
			String toolName) {
		String toolDesc = toolName != null ? "'" + toolName + "' tool call" : "Tool call";
		List<String> exceededLimits = new ArrayList<>();

		if (threadLimit != null && threadCount >= threadLimit) {
			exceededLimits.add(String.format("thread limit (%d/%d)", threadCount, threadLimit));
		}
		if (runLimit != null && runCount >= runLimit) {
			exceededLimits.add(String.format("run limit (%d/%d)", runCount, runLimit));
		}

		return toolDesc + " limits exceeded: " + String.join(", ", exceededLimits);
	}

	@Override
	public String getName() {
		return toolName != null ? "ToolCallLimit[" + toolName + "]" : "ToolCallLimit[all]";
	}

	@Override
	public List<JumpTo> canJumpTo() {
		if (exitBehavior == ExitBehavior.END) {
			return List.of(JumpTo.end);
		}
		return List.of(JumpTo.model);
	}

	@Override
	public Map<String, KeyStrategy> getKeyStrategys() {
		return threadLimit == null ? Map.of() : Map.of(getThreadCountKey(), KeyStrategy.REPLACE);
	}

	public enum ExitBehavior {

		END, ERROR

	}

	public static class Builder {

		private String toolName;

		private Integer threadLimit;

		private Integer runLimit;

		private ExitBehavior exitBehavior = ExitBehavior.END;

		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

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

		public ToolCallLimitHook build() {
			if (threadLimit == null && runLimit == null) {
				throw new IllegalArgumentException("At least one limit must be specified (threadLimit or runLimit)");
			}
			return new ToolCallLimitHook(this);
		}

	}

}
