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
package io.github.agentic.ai.graph.agent.extension.interceptor;

import io.github.agentic.ai.graph.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware to patch dangling tool calls in the messages history.
 *
 * <p>
 * This interceptor handles situations where an AI message contains tool calls that don't
 * have corresponding tool results in the conversation history. This can happen when tool
 * execution is interrupted or when new messages arrive before tool responses can be
 * added.
 * </p>
 *
 * <p>
 * The interceptor automatically adds cancellation messages for any dangling tool calls,
 * preventing errors when the conversation is sent to the model.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>
 * PatchToolCallsInterceptor interceptor = PatchToolCallsInterceptor.builder().build();
 * agent.addInterceptor(interceptor);
 * </pre>
 */
public class PatchToolCallsInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(PatchToolCallsInterceptor.class);

	private static final String CANCELLATION_MESSAGE_TEMPLATE = "Tool call %s with id %s was cancelled - another message came in before it could be completed.";

	private PatchToolCallsInterceptor(Builder builder) {
		// Currently no configuration options, but builder pattern allows future
		// extensibility
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String getName() {
		return "PatchToolCalls";
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		List<ChatMessage> messages = request.getMessages();

		if (messages == null || messages.isEmpty()) {
			return handler.call(request);
		}

		List<ChatMessage> patchedMessages = patchDanglingToolCalls(messages);

		// If messages were patched, create a new request with the updated messages
		if (patchedMessages != messages) {
			ModelRequest patchedRequest = ModelRequest.builder(request).messages(patchedMessages).build();
			return handler.call(patchedRequest);
		}

		return handler.call(request);
	}

	/**
	 * Patch dangling tool calls by adding tool results for any tool calls that don't have
	 * corresponding responses.
	 * @param messages The original message list
	 * @return A new list with patched messages, or the original list if no patching
	 * needed
	 */
	private List<ChatMessage> patchDanglingToolCalls(List<ChatMessage> messages) {
		List<ChatMessage> patchedMessages = new ArrayList<>();
		boolean hasPatches = false;

		// Iterate through messages and patch dangling tool calls
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage msg = messages.get(i);
			patchedMessages.add(msg);

			// Only contiguous results belong to this assistant turn. Earlier or later
			// turns must not hide a missing result, even if providers reuse call IDs.
			if (msg.role() == ChatRole.ASSISTANT) {
				List<ToolCall> toolCalls = msg.toolCalls();

				if (!toolCalls.isEmpty()) {
					Set<String> existingToolResponseIds = new HashSet<>();
					while (i + 1 < messages.size() && messages.get(i + 1).role() == ChatRole.TOOL) {
						ChatMessage result = messages.get(++i);
						patchedMessages.add(result);
						existingToolResponseIds.add(result.toolCallId());
					}
					// Check each tool call to see if it has a corresponding response
					List<ChatMessage> missingResponses = new ArrayList<>();

					for (ToolCall toolCall : toolCalls) {
						String toolCallId = toolCall.id();

						// Check whether this turn already has a result for the call.
						boolean hasResponse = existingToolResponseIds.contains(toolCallId);

						if (!hasResponse) {
							// Found a dangling tool call - create a cancellation response
							String cancellationMsg = String.format(CANCELLATION_MESSAGE_TEMPLATE, toolCall.name(),
									toolCallId);

							missingResponses
								.add(new ChatMessage(ChatRole.TOOL, List.of(ContentPart.text(cancellationMsg)),
										List.of(), toolCallId, toolCall.name(), Map.of("patched", true)));

							log.info("Patching dangling tool call: {} (id: {})", toolCall.name(), toolCallId);
						}
					}

					// Complete the turn before forwarding the next user or assistant
					// message.
					if (!missingResponses.isEmpty()) {
						patchedMessages.addAll(missingResponses);
						hasPatches = true;
					}
				}
			}
		}

		return hasPatches ? patchedMessages : messages;
	}

	/**
	 * Builder for creating PatchToolCallsInterceptor instances.
	 */
	public static class Builder {

		public Builder() {
		}

		/**
		 * Build the PatchToolCallsInterceptor instance.
		 * @return A new PatchToolCallsInterceptor
		 */
		public PatchToolCallsInterceptor build() {
			return new PatchToolCallsInterceptor(this);
		}

	}

}
