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
package io.github.agentic.ai.graph.agent.hook.summarization;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.TokenCounter;
import io.github.agentic.ai.graph.agent.hook.messages.AgentCommand;
import io.github.agentic.ai.graph.agent.hook.messages.UpdatePolicy;
import io.github.agentic.ai.graph.agent.hook.messages.MessagesModelHook;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook that summarizes conversation history when token limits are approached.
 *
 * This hook monitors message token counts and automatically summarizes older messages
 * when a threshold is reached, preserving the first user message and recent messages to
 * maintain context continuity.
 *
 * Example: SummarizationHook summarizer = SummarizationHook.builder()
 * .chatClient(summaryClient) .maxTokensBeforeSummary(4000) .messagesToKeep(20)
 * .keepFirstUserMessage(true) // Default: true .build();
 */
@HookPositions({ HookPosition.BEFORE_MODEL })
public class SummarizationHook extends MessagesModelHook {

	private static final Logger log = LoggerFactory.getLogger(SummarizationHook.class);

	private static final String DEFAULT_SUMMARY_PROMPT = "<role>\nContext Extraction Assistant\n</role>\n\n"
			+ "<primary_objective>\n"
			+ "Your sole objective in this task is to extract the highest quality/most relevant context "
			+ "from the conversation history below.\n</primary_objective>\n\n" + "<instructions>\n"
			+ "The conversation history below will be replaced with the context you extract in this step. "
			+ "Extract and record all of the most important context from the conversation history.\n"
			+ "Respond ONLY with the extracted context. Do not include any additional information.\n"
			+ "</instructions>\n\n" + "<messages>\nMessages to summarize:\n%s\n</messages>";

	private static final String SUMMARY_PREFIX = "## Previous conversation summary:";

	private static final int DEFAULT_MESSAGES_TO_KEEP = 20;

	private static final boolean DEFAULT_KEEP_FIRST_USER_MESSAGE = true;

	private final ChatClient chatClient;

	private final Integer maxTokensBeforeSummary;

	private final int messagesToKeep;

	private final TokenCounter tokenCounter;

	private final String summaryPrompt;

	private final String summaryPrefix;

	private final boolean keepFirstUserMessage;

	private SummarizationHook(Builder builder) {
		this.chatClient = builder.chatClient;
		this.maxTokensBeforeSummary = builder.maxTokensBeforeSummary;
		this.messagesToKeep = builder.messagesToKeep;
		this.tokenCounter = builder.tokenCounter;
		this.summaryPrompt = builder.summaryPrompt;
		this.summaryPrefix = builder.summaryPrefix;
		this.keepFirstUserMessage = builder.keepFirstUserMessage;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentCommand beforeModel(List<ChatMessage> previousMessages, RunnableConfig config) {
		if (maxTokensBeforeSummary == null) {
			return new AgentCommand(previousMessages);
		}

		int totalTokens = tokenCounter.countTokens(previousMessages);

		if (totalTokens < maxTokensBeforeSummary) {
			return new AgentCommand(previousMessages);
		}

		log.info("Token count {} exceeds threshold {}, triggering summarization", totalTokens, maxTokensBeforeSummary);

		int cutoffIndex = findSafeCutoff(previousMessages);

		if (cutoffIndex <= 0) {
			log.warn("Cannot find safe cutoff point for summarization");
			return new AgentCommand(previousMessages);
		}

		ChatMessage firstUserMessage = null;
		if (keepFirstUserMessage) {
			for (ChatMessage msg : previousMessages) {
				if (msg instanceof ChatMessage && ((ChatMessage) msg).role() == ChatRole.USER) {
					firstUserMessage = (ChatMessage) msg;
					break;
				}
			}
		}

		List<ChatMessage> toSummarize = new ArrayList<>();
		for (int i = 0; i < cutoffIndex; i++) {
			ChatMessage msg = previousMessages.get(i);
			if (msg != firstUserMessage) {
				toSummarize.add(msg);
			}
		}

		final String summary;
		try {
			summary = createSummary(toSummarize);
		}
		catch (Exception e) {
			// A failed summary must never replace the original conversation with an error
			// message.
			log.warn("Failed to create summary; preserving conversation history", e);
			return new AgentCommand(previousMessages);
		}

		ChatMessage summaryMessage = ChatMessage.system(summaryPrefix + "\n" + summary);

		List<ChatMessage> recentMessages = new ArrayList<>();
		for (int i = cutoffIndex; i < previousMessages.size(); i++) {
			recentMessages.add(previousMessages.get(i));
		}

		List<ChatMessage> newMessages = new ArrayList<>();
		if (firstUserMessage != null && !recentMessages.contains(firstUserMessage)) {
			newMessages.add(firstUserMessage);
		}
		newMessages.add(summaryMessage);
		newMessages.addAll(recentMessages);

		if (firstUserMessage != null) {
			log.info("Summarized {} messages, keeping {} recent messages (First ChatMessage preserved)",
					toSummarize.size(), recentMessages.size());
		}
		else {
			log.info("Summarized {} messages, keeping {} recent messages", toSummarize.size(), recentMessages.size());
		}

		return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
	}

	/**
	 * Find safe cutoff point that preserves AI/Tool message pairs.
	 *
	 * Returns the index where messages can be safely cut without separating related AI
	 * and Tool messages. Returns 0 if no safe cutoff is found.
	 */
	private int findSafeCutoff(List<ChatMessage> messages) {
		if (messages.size() <= messagesToKeep) {
			return 0;
		}

		int targetCutoff = messages.size() - messagesToKeep;

		// Each call/result pair forbids cutoffs between its endpoints. Index intervals
		// once so delayed or overlapping results do not require repeated history scans.
		int[] forbiddenEnds = new int[messages.size() + 1];
		Map<String, Integer> calls = new HashMap<>();
		Map<String, Integer> pending = new HashMap<>();
		for (int index = 0; index < messages.size(); index++) {
			ChatMessage message = messages.get(index);
			if (message.role() == ChatRole.ASSISTANT) {
				for (io.github.agentic.ai.api.chat.ToolCall call : message.toolCalls()) {
					calls.putIfAbsent(call.id(), index);
					pending.putIfAbsent(call.id(), index);
				}
			}
			else if (message.role() == ChatRole.TOOL) {
				Integer callIndex = calls.get(message.toolCallId());
				if (callIndex != null) {
					int start = callIndex + 1;
					forbiddenEnds[start] = Math.max(forbiddenEnds[start], index);
					pending.remove(message.toolCallId());
				}
			}
		}
		// Unresolved calls must remain available when a paused conversation resumes.
		for (int callIndex : pending.values()) {
			forbiddenEnds[callIndex + 1] = messages.size();
		}
		int safeCutoff = 0;
		int forbiddenUntil = 0;
		for (int index = 1; index <= targetCutoff; index++) {
			forbiddenUntil = Math.max(forbiddenUntil, forbiddenEnds[index]);
			if (index > forbiddenUntil) {
				safeCutoff = index;
			}
		}
		return safeCutoff;
	}

	private String createSummary(List<ChatMessage> messages) {
		if (messages.isEmpty()) {
			return "No previous conversation.";
		}

		StringBuilder messageText = new StringBuilder();
		for (ChatMessage msg : messages) {
			String role = getRoleName(msg);
			messageText.append(role).append(": ").append(msg.text() != null ? msg.text() : "").append("\n");
			if (msg.role() == ChatRole.ASSISTANT) {
				for (io.github.agentic.ai.api.chat.ToolCall toolCall : msg.toolCalls()) {
					messageText.append("Tool call [id=")
						.append(toolCall.id())
						.append(", name=")
						.append(toolCall.name())
						.append("]: ")
						.append(toolCall.arguments())
						.append("\n");
				}
			}
			else if (msg.role() == ChatRole.TOOL) {
				messageText.append("Tool result [id=")
					.append(msg.toolCallId())
					.append(", name=")
					.append(msg.toolName())
					.append("]: ")
					.append(msg.text())
					.append("\n");
			}
		}

		String prompt = String.format(summaryPrompt, messageText.toString());

		String summary = chatClient.callBlocking(ChatRequest.of(List.of(ChatMessage.user(prompt)))).text();
		if (summary.isBlank()) {
			throw new IllegalStateException("Summary chatClient returned an empty result");
		}
		return summary;
	}

	private String getRoleName(ChatMessage message) {
		if (message instanceof ChatMessage && ((ChatMessage) message).role() == ChatRole.USER) {
			return "Human";
		}
		else if (message instanceof ChatMessage && ((ChatMessage) message).role() == ChatRole.ASSISTANT) {
			return "Assistant";
		}
		else if (message instanceof ChatMessage && ((ChatMessage) message).role() == ChatRole.SYSTEM) {
			return "System";
		}
		else if (message.role() == ChatRole.TOOL) {
			return "Tool";
		}
		else {
			return "Unknown";
		}
	}

	@Override
	public String getName() {
		return "Summarization";
	}

	@Override
	public List<JumpTo> canJumpTo() {
		return List.of();
	}

	public static class Builder {

		private ChatClient chatClient;

		private Integer maxTokensBeforeSummary;

		private int messagesToKeep = DEFAULT_MESSAGES_TO_KEEP;

		private TokenCounter tokenCounter = TokenCounter.approximateMsgCounter();

		private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;

		private String summaryPrefix = SUMMARY_PREFIX;

		private boolean keepFirstUserMessage = DEFAULT_KEEP_FIRST_USER_MESSAGE;

		/** Set the provider-neutral chat client used to summarize history. */
		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder maxTokensBeforeSummary(Integer maxTokens) {
			if (maxTokens != null && maxTokens <= 0) {
				throw new IllegalArgumentException("maxTokensBeforeSummary must be positive");
			}
			this.maxTokensBeforeSummary = maxTokens;
			return this;
		}

		public Builder messagesToKeep(int count) {
			if (count < 0) {
				throw new IllegalArgumentException("messagesToKeep must be non-negative");
			}
			this.messagesToKeep = count;
			return this;
		}

		public Builder summaryPrompt(String prompt) {
			this.summaryPrompt = prompt;
			return this;
		}

		public Builder summaryPrefix(String prefix) {
			this.summaryPrefix = prefix;
			return this;
		}

		public Builder tokenCounter(TokenCounter counter) {
			this.tokenCounter = counter;
			return this;
		}

		public Builder keepFirstUserMessage(boolean keep) {
			this.keepFirstUserMessage = keep;
			return this;
		}

		public SummarizationHook build() {
			if (chatClient == null) {
				throw new IllegalArgumentException("Summary chatClient must be specified");
			}
			return new SummarizationHook(this);
		}

	}

}
