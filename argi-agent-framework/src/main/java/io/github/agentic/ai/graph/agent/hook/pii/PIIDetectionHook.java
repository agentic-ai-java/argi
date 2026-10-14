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
package io.github.agentic.ai.graph.agent.hook.pii;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.graph.agent.hook.messages.UpdatePolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detect and handle Personally Identifiable Information (PII) in agent conversations.
 *
 * This hook detects common PII types and applies configurable strategies to handle them.
 * It can detect emails, credit cards, IP addresses, MAC addresses, and URLs in both user
 * input and agent output.
 *
 * Example: PIIDetectionHook pii = PIIDetectionHook.builder() .piiType(PIIType.EMAIL)
 * .strategy(RedactionStrategy.REDACT) .applyToInput(true) .build();
 */
@HookPositions({ HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL })
public class PIIDetectionHook extends MessagesModelHook {

	private final PIIType piiType;

	private final RedactionStrategy strategy;

	private final PIIDetector detector;

	private final boolean applyToInput;

	private final boolean applyToOutput;

	private final boolean applyToToolResults;

	private PIIDetectionHook(Builder builder) {
		this.piiType = builder.piiType;
		this.strategy = builder.strategy;
		this.detector = builder.detector != null ? builder.detector : getDefaultDetector(piiType);
		this.applyToInput = builder.applyToInput;
		this.applyToOutput = builder.applyToOutput;
		this.applyToToolResults = builder.applyToToolResults;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentCommand beforeModel(List<ChatMessage> previousMessages, RunnableConfig config) {
		List<ChatMessage> processedMessages = new ArrayList<>();
		boolean hasChanges = false;

		for (ChatMessage message : previousMessages) {
			ChatMessage processed = processMessage(message);
			processedMessages.add(processed);
			if (processed != message) {
				hasChanges = true;
			}
		}

		if (hasChanges) {
			return new AgentCommand(processedMessages, UpdatePolicy.REPLACE);
		}

		return new AgentCommand(previousMessages);
	}

	@Override
	public AgentCommand afterModel(List<ChatMessage> previousMessages, RunnableConfig config) {
		// Only process if applyToOutput is enabled
		if (!applyToOutput) {
			return new AgentCommand(previousMessages);
		}

		if (previousMessages.isEmpty()) {
			return new AgentCommand(previousMessages);
		}

		// Find the last AI message
		ChatMessage aiMessage = null;
		int lastIndex = -1;
		for (int i = previousMessages.size() - 1; i >= 0; i--) {
			if (previousMessages.get(i).role() == ChatRole.ASSISTANT) {
				aiMessage = previousMessages.get(i);
				lastIndex = i;
				break;
			}
		}

		if (aiMessage == null) {
			return new AgentCommand(previousMessages);
		}

		String content = aiMessage.text();

		if (content == null || content.isEmpty()) {
			return new AgentCommand(previousMessages);
		}

		// Detect PII
		ProcessResult result = processText(content);

		if (!result.hasMatches) {
			return new AgentCommand(previousMessages);
		}

		// Apply strategy
		if (result.hasMatches && strategy == RedactionStrategy.BLOCK) {
			throw new PIIDetectionException(piiType.name(), result.matches);
		}

		if (result.redactedText.equals(content)) {
			return new AgentCommand(previousMessages);
		}

		// Create updated message
		ChatMessage updatedMessage = aiMessage.withText(result.redactedText);

		List<ChatMessage> updatedMessages = new ArrayList<>(previousMessages);
		updatedMessages.set(lastIndex, updatedMessage);

		return new AgentCommand(updatedMessages, UpdatePolicy.REPLACE);
	}

	private ChatMessage processMessage(ChatMessage message) {
		boolean process = switch (message.role()) {
			case USER -> applyToInput;
			case ASSISTANT -> applyToOutput;
			case TOOL -> applyToToolResults;
			default -> false;
		};
		if (!process || message.text().isEmpty()) {
			return message;
		}
		ProcessResult result = processText(message.text());
		if (result.hasMatches && strategy == RedactionStrategy.BLOCK) {
			throw new PIIDetectionException(piiType.name(), result.matches);
		}
		return result.redactedText.equals(message.text()) ? message : message.withText(result.redactedText);
	}

	private ProcessResult processText(String text) {
		List<PIIMatch> matches = detector.detect(text);

		if (matches.isEmpty()) {
			return new ProcessResult(text, false, matches);
		}

		String redacted = applyStrategy(text, matches);
		return new ProcessResult(redacted, true, matches);
	}

	private String applyStrategy(String text, List<PIIMatch> matches) {
		if (matches.isEmpty()) {
			return text;
		}

		StringBuilder result = new StringBuilder();
		int lastEnd = 0;

		// Sort matches by start position
		matches.sort(Comparator.comparingInt(m -> m.start));

		for (PIIMatch match : matches) {
			result.append(text, lastEnd, match.start);

			switch (strategy) {
				case REDACT:
					result.append("[REDACTED_").append(piiType.name()).append("]");
					break;
				case MASK:
					result.append(maskValue(match.value));
					break;
				case HASH:
					result.append(hashValue(match.value));
					break;
				case BLOCK:
					// Already handled in processText
					break;
			}

			lastEnd = match.end;
		}

		result.append(text.substring(lastEnd));
		return result.toString();
	}

	private String maskValue(String value) {
		if (value.length() <= 4) {
			return "****";
		}
		int visibleChars = 4;
		String masked = "*".repeat(value.length() - visibleChars);
		return masked + value.substring(value.length() - visibleChars);
	}

	private String hashValue(String value) {
		int hash = value.hashCode();
		return String.format("<%s_hash:%08x>", piiType.name().toLowerCase(), hash);
	}

	private PIIDetector getDefaultDetector(PIIType type) {
		switch (type) {
			case EMAIL:
				return PIIDetectors.emailDetector();
			case CREDIT_CARD:
				return PIIDetectors.creditCardDetector();
			case IP:
				return PIIDetectors.ipDetector();
			case MAC_ADDRESS:
				return PIIDetectors.macAddressDetector();
			case URL:
				return PIIDetectors.urlDetector();
			default:
				throw new IllegalArgumentException("No default detector for PII type: " + type);
		}
	}

	@Override
	public String getName() {
		return "PIIDetection[" + piiType.name() + "]";
	}

	@Override
	public List<JumpTo> canJumpTo() {
		return List.of();
	}

	private static class ProcessResult {

		final String redactedText;

		final boolean hasMatches;

		final List<PIIMatch> matches;

		ProcessResult(String redactedText, boolean hasMatches, List<PIIMatch> matches) {
			this.redactedText = redactedText;
			this.hasMatches = hasMatches;
			this.matches = matches;
		}

	}

	public static class Builder {

		private PIIType piiType;

		private RedactionStrategy strategy = RedactionStrategy.REDACT;

		private PIIDetector detector;

		private boolean applyToInput = true;

		private boolean applyToOutput = false;

		private boolean applyToToolResults = false;

		public Builder piiType(PIIType piiType) {
			this.piiType = piiType;
			return this;
		}

		public Builder strategy(RedactionStrategy strategy) {
			this.strategy = strategy;
			return this;
		}

		public Builder detector(PIIDetector detector) {
			this.detector = detector;
			return this;
		}

		public Builder applyToInput(boolean applyToInput) {
			this.applyToInput = applyToInput;
			return this;
		}

		public Builder applyToOutput(boolean applyToOutput) {
			this.applyToOutput = applyToOutput;
			return this;
		}

		public Builder applyToToolResults(boolean applyToToolResults) {
			this.applyToToolResults = applyToToolResults;
			return this;
		}

		public PIIDetectionHook build() {
			if (piiType == null) {
				throw new IllegalArgumentException("piiType must be specified");
			}
			return new PIIDetectionHook(this);
		}

	}

}
