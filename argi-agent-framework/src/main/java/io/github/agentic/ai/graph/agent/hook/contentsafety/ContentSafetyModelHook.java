/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.hook.contentsafety;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.hook.messages.UpdatePolicy;
import io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in message checks for request ingress, model egress, child results, receipts, and
 * return-direct tool observations. Tool dispatch itself is enforced by
 * {@code ContentSafetyToolInterceptor}.
 */
@HookPositions({ HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL })
public class ContentSafetyModelHook extends MessagesModelHook {

	private final ContentSafetyGate gate;

	public ContentSafetyModelHook(ContentSafetyGate gate) {
		this.gate = gate;
	}

	@Override
	public String getName() {
		return "ContentSafetyModel";
	}

	@Override
	public AgentCommand beforeModel(List<ChatMessage> previousMessages, RunnableConfig config) {
		return rewrite(previousMessages, false);
	}

	@Override
	public AgentCommand afterModel(List<ChatMessage> previousMessages, RunnableConfig config) {
		return rewrite(previousMessages, true);
	}

	private AgentCommand rewrite(List<ChatMessage> messages, boolean afterModel) {
		List<ChatMessage> updated = new ArrayList<>(messages.size());
		boolean changed = false;
		for (ChatMessage message : messages) {
			ChatMessage next = inspectMessage(message, afterModel);
			updated.add(next);
			if (next != message) {
				changed = true;
			}
		}
		return changed ? new AgentCommand(updated, UpdatePolicy.REPLACE) : new AgentCommand(messages);
	}

	private ChatMessage inspectMessage(ChatMessage message, boolean afterModel) {
		ContentSafetyStage stage = stageFor(message, afterModel);
		if (stage == null) {
			return message;
		}
		ContentSafetyDecision decision = gate.inspect(stage, message.text());
		ChatMessage current = message;
		if (!decision.output().equals(message.text() == null ? "" : message.text())) {
			current = message.withText(decision.output()).withMetadata(merge(message, decision));
		}
		if (afterModel && current.role() == ChatRole.ASSISTANT && current.hasToolCalls()) {
			current = inspectToolCalls(current);
		}
		return current;
	}

	private ChatMessage inspectToolCalls(ChatMessage message) {
		List<ToolCall> kept = new ArrayList<>();
		boolean changed = false;
		for (ToolCall call : message.toolCalls()) {
			ContentSafetyDecision decision = gate.inspect(ContentSafetyStage.TOOL_ARGS, call.arguments());
			if (decision.blocksDispatch()) {
				changed = true;
				continue;
			}
			if (!decision.output().equals(call.arguments())) {
				changed = true;
				continue;
			}
			kept.add(call);
		}
		if (!changed) {
			return message;
		}
		ChatMessage stripped = message.withToolCalls(kept);
		if (kept.isEmpty()) {
			ContentSafetyDecision blocked = gate.inspect(ContentSafetyStage.FINAL_OUTPUT, message.text());
			String text = blocked.blocksDispatch() ? blocked.output()
					: ContentSafetyGate.statusText(ContentSafetyStage.TOOL_ARGS,
							io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyAction.BLOCK,
							ContentSafetyKeys.REASON_MATCHED);
			return stripped.withText(text);
		}
		return stripped;
	}

	private ContentSafetyStage stageFor(ChatMessage message, boolean afterModel) {
		if (Boolean.TRUE.equals(message.metadata().get(ContentSafetyKeys.CHILD_RESULT))) {
			return ContentSafetyStage.CHILD_RESULT;
		}
		if (Boolean.TRUE.equals(message.metadata().get(ContentSafetyKeys.CACHED_RECEIPT))) {
			return ContentSafetyStage.RECEIPT;
		}
		if (message.role() == ChatRole.TOOL
				&& ReturnDirectConstants.FINISH_REASON.equals(message.metadata().get(ReturnDirectConstants.FINISH_REASON_METADATA_KEY))) {
			return ContentSafetyStage.RETURN_DIRECT;
		}
		if (message.role() == ChatRole.USER) {
			return ContentSafetyStage.REQUEST_INGRESS;
		}
		if (message.role() == ChatRole.TOOL) {
			return ContentSafetyStage.TOOL_RESULT;
		}
		if (afterModel && message.role() == ChatRole.ASSISTANT) {
			return ContentSafetyStage.FINAL_OUTPUT;
		}
		if (!afterModel && message.role() == ChatRole.ASSISTANT) {
			return ContentSafetyStage.MODEL_EGRESS;
		}
		return null;
	}

	private java.util.Map<String, Object> merge(ChatMessage message, ContentSafetyDecision decision) {
		java.util.Map<String, Object> metadata = new java.util.HashMap<>(message.metadata());
		metadata.putAll(decision.status());
		return metadata;
	}

}
