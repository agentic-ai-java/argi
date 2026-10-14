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
package io.github.agentic.ai.graph.agent.hook.hip;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.agent.hook.AbstractInterruptableModelHook;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import io.github.agentic.ai.graph.agent.hook.Hook;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.state.RemoveByHash;
import io.github.agentic.ai.graph.utils.TypeRef;

import io.github.agentic.ai.api.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@HookPositions(HookPosition.AFTER_MODEL)
public class HumanInTheLoopHook extends AbstractInterruptableModelHook {

	private static final Logger log = LoggerFactory.getLogger(HumanInTheLoopHook.class);

	public static final String HITL_NODE_NAME = "HITL";

	private Map<String, ToolConfig> approvalOn;

	private HumanInTheLoopHook(Builder builder) {
		this.approvalOn = new HashMap<>(builder.approvalOn);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
		return afterModel(state, config);
	}

	@Override
	public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
		Optional<InterruptionMetadata> feedback = config
			.getMetadataAndRemove(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, new TypeRef<InterruptionMetadata>() {
			});
		InterruptionMetadata interruptionMetadata = feedback.orElse(null);

		if (interruptionMetadata == null) {
			log.debug(
					"No human feedback found in the runnable config metadata, no tool to execute or none needs feedback.");
			return CompletableFuture.completedFuture(Map.of());
		}

		ChatMessage assistantMessage = getLastAssistantMessage(state);

		if (assistantMessage != null) {

			if (!assistantMessage.hasToolCalls()) {
				log.info(
						"Found human feedback but last ChatMessage has no tool calls, nothing to process for human feedback.");
				return CompletableFuture.completedFuture(Map.of());
			}

			List<ToolCall> pendingCalls = pendingToolCalls(state, assistantMessage);
			if (!validateFeedback(interruptionMetadata, pendingCalls)) {
				throw new IllegalArgumentException(
						"Human feedback must uniquely identify every pending approval and provide an explicit result.");
			}

			List<io.github.agentic.ai.api.chat.ToolCall> newToolCalls = new ArrayList<>();

			List<ChatMessage> responses = new ArrayList<>();

			for (io.github.agentic.ai.api.chat.ToolCall toolCall : assistantMessage.toolCalls()) {
				if (!pendingCalls.contains(toolCall)) {
					newToolCalls.add(toolCall);
					continue;
				}
				Optional<ToolFeedback> toolFeedbackOpt = interruptionMetadata.toolFeedbacks()
					.stream()
					.filter(tf -> toolCall.id().equals(tf.getId()) && toolCall.name().equals(tf.getName()))
					.findFirst();

				if (toolFeedbackOpt.isPresent()) {
					ToolFeedback toolFeedback = toolFeedbackOpt.get();
					FeedbackResult result = toolFeedback.getResult();

					if (result == FeedbackResult.APPROVED) {
						newToolCalls.add(toolCall);
					}
					else if (result == FeedbackResult.EDITED) {
						io.github.agentic.ai.api.chat.ToolCall editedToolCall = new io.github.agentic.ai.api.chat.ToolCall(
								toolCall.id(), toolCall.name(), toolFeedback.getArguments());
						newToolCalls.add(editedToolCall);
					}
					else if (result == FeedbackResult.REJECTED) {
						newToolCalls.add(toolCall);
						ChatMessage response = ToolResult
							.error(String.format("Tool call request for %s has been rejected by human. Reason:\n %s.",
									toolFeedback.getName(), toolFeedback.getDescription()))
							.toMessage(toolCall);
						responses.add(response);
					}
				}
				else {
					// Only calls outside approvalOn may proceed without explicit
					// feedback.
					newToolCalls.add(toolCall);
				}
			}

			Map<String, Object> updates = new HashMap<>();
			List<Object> newMessages = new ArrayList<>();

			if (!newToolCalls.isEmpty()) {
				// Replace the last message with the new assistant message containing
				// updated tool calls
				newMessages.add(assistantMessage.withToolCalls(newToolCalls));
				newMessages.add(new RemoveByHash<>(assistantMessage));
			}

			// Results follow the assistant turn and retain individual call identities.
			if (!responses.isEmpty()) {
				newMessages.addAll(responses);
			}

			updates.put("messages", newMessages);
			return CompletableFuture.completedFuture(updates);
		}
		else {
			log.warn("Last message is not an ChatMessage, cannot process human feedback.");
		}

		return CompletableFuture.completedFuture(Map.of());
	}

	@Override
	public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
		ChatMessage lastMessage = getLastAssistantMessage(state);

		if (lastMessage == null || !lastMessage.hasToolCalls()) {
			return Optional.empty();
		}

		Optional<Object> feedback = config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY);
		if (feedback.isPresent()) {
			if (!(feedback.get() instanceof InterruptionMetadata)) {
				throw new IllegalArgumentException("Human feedback metadata must be of type InterruptionMetadata.");
			}

			if (!validateFeedback((InterruptionMetadata) feedback.get(), pendingToolCalls(state, lastMessage))) {
				return buildInterruptionMetadata(state, lastMessage);
			}
			return Optional.empty();
		}

		// 2. If last message is ChatMessage
		return buildInterruptionMetadata(state, lastMessage);
	}

	private static ChatMessage getLastAssistantMessage(OverAllState state) {
		List<ChatMessage> messages = (List<ChatMessage>) state.value("messages").orElse(List.of());

		java.util.Set<String> completed = new java.util.HashSet<>();
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage msg = messages.get(i);
			if (msg.role() == ChatRole.TOOL) {
				completed.add(msg.toolCallId());
			}
			else if (msg.role() == ChatRole.ASSISTANT) {
				List<ToolCall> pending = msg.toolCalls()
					.stream()
					.filter(call -> !completed.contains(call.id()))
					.toList();
				return pending.isEmpty() ? null : msg;
			}
			else {
				return null;
			}
		}
		return null;
	}

	private static List<ToolCall> pendingToolCalls(OverAllState state, ChatMessage assistantMessage) {
		List<ChatMessage> messages = (List<ChatMessage>) state.value("messages").orElse(List.of());
		java.util.Set<String> completed = new java.util.HashSet<>();
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage message = messages.get(i);
			if (message == assistantMessage) {
				break;
			}
			if (message.role() == ChatRole.TOOL) {
				completed.add(message.toolCallId());
			}
		}
		return assistantMessage.toolCalls().stream().filter(call -> !completed.contains(call.id())).toList();
	}

	private Optional<InterruptionMetadata> buildInterruptionMetadata(OverAllState state, ChatMessage lastMessage) {
		boolean needsInterruption = false;
		InterruptionMetadata.Builder builder = InterruptionMetadata.builder(Hook.getFullHookName(this), state);
		for (io.github.agentic.ai.api.chat.ToolCall toolCall : pendingToolCalls(state, lastMessage)) {
			if (approvalOn.containsKey(toolCall.name())) {
				ToolConfig toolConfig = approvalOn.get(toolCall.name());
				String description = toolConfig.getDescription();
				String content = "The AI is requesting to use the tool: " + toolCall.name() + ".\n"
						+ (description != null ? ("Description: " + description + "\n") : "")
						+ "With the following arguments: " + toolCall.arguments() + "\n" + "Do you approve?";
				builder.addToolFeedback(ToolFeedback.builder()
					.id(toolCall.id())
					.name(toolCall.name())
					.description(content)
					.arguments(toolCall.arguments())
					.build());
				needsInterruption = true;
			}
			else {
				builder
					.addToolsAutomaticallyApproved(new ToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()));
			}
		}
		return needsInterruption ? Optional.of(builder.build()) : Optional.empty();
	}

	private boolean validateFeedback(InterruptionMetadata feedback,
			List<io.github.agentic.ai.api.chat.ToolCall> toolCalls) {
		if (feedback == null) {
			return false;
		}

		// Validate against the current pending calls, never client-supplied automatic
		// approvals.
		List<io.github.agentic.ai.api.chat.ToolCall> toolCallsNeedingApproval = toolCalls.stream()
			.filter(tc -> approvalOn.containsKey(tc.name()))
			.toList();

		Map<String, ToolFeedback> feedbackById = new HashMap<>();
		for (ToolFeedback entry : feedback.toolFeedbacks()) {
			boolean matchesPending = toolCallsNeedingApproval.stream()
				.anyMatch(call -> call.id().equals(entry.getId()) && call.name().equals(entry.getName()));
			if (!matchesPending || feedbackById.putIfAbsent(entry.getId(), entry) != null) {
				log.warn("Unexpected or duplicate tool feedback; waiting for human input.");
				return false;
			}
			if (entry.getResult() == FeedbackResult.EDITED
					&& (entry.getArguments() == null || entry.getArguments().isBlank())) {
				log.warn("Edited tool feedback must include arguments; waiting for human input.");
				return false;
			}
		}

		for (io.github.agentic.ai.api.chat.ToolCall call : toolCallsNeedingApproval) {
			ToolFeedback matchedFeedback = feedbackById.get(call.id());

			if (matchedFeedback == null) {
				log.warn("Missing feedback for tool {} (id={}); waiting for human input.", call.name(), call.id());
				return false;
			}

			if (matchedFeedback.getResult() == null) {
				log.warn("Feedback result for tool {} (id={}) is null; waiting for human input.", call.name(),
						call.id());
				return false;
			}
		}

		return true;
	}

	@Override
	public String getName() {
		return HITL_NODE_NAME;
	}

	@Override
	public List<JumpTo> canJumpTo() {
		return List.of();
	}

	public static class Builder {

		private Map<String, ToolConfig> approvalOn = new HashMap<>();

		public Builder approvalOn(String toolName, ToolConfig toolConfig) {
			this.approvalOn.put(toolName, toolConfig);
			return this;
		}

		public Builder approvalOn(String toolName, String description) {
			ToolConfig config = new ToolConfig();
			config.setDescription(description);
			this.approvalOn.put(toolName, config);
			return this;
		}

		public Builder approvalOn(Map<String, ToolConfig> approvalOn) {
			this.approvalOn.putAll(approvalOn);
			return this;
		}

		public HumanInTheLoopHook build() {
			return new HumanInTheLoopHook(this);
		}

	}

}
