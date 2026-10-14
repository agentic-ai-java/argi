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
package io.github.agentic.ai.graph.agent.flow.node;

import io.github.agentic.ai.api.chat.ChatMessage;

import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.action.NodeAction;
import io.github.agentic.ai.graph.agent.BaseAgent;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Dedicated node for merging routing results. Collects outputs from sub-agents routed by
 * LlmRoutingAgent, synthesizes them via LLM into a single coherent answer, and writes the
 * merged result to state.
 */
public class RoutingMergeNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(RoutingMergeNode.class);

	/** Default key for the merged result in state. */
	public static final String DEFAULT_MERGED_OUTPUT_KEY = "merged_result";

	private static final String SYNTHESIZE_SYSTEM_TEMPLATE = """
			Synthesize these search results to answer the original question: "%s"

			- Combine information from multiple sources without redundancy
			- Highlight the most relevant and actionable information
			- Note any discrepancies between sources
			- Keep the response concise and well-organized
			""";

	private final ChatClient chatClient;

	private final List<BaseAgent> subAgents;

	private final String mergedOutputKey;

	public RoutingMergeNode(ChatClient chatClient, List<BaseAgent> subAgents) {
		this(chatClient, subAgents, DEFAULT_MERGED_OUTPUT_KEY);
	}

	public RoutingMergeNode(ChatClient chatClient, List<BaseAgent> subAgents, String mergedOutputKey) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		this.subAgents = List.copyOf(subAgents);
		this.mergedOutputKey = mergedOutputKey != null ? mergedOutputKey : DEFAULT_MERGED_OUTPUT_KEY;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.debug("RoutingMergeNode: merging results from {} sub-agents", subAgents.size());

		List<String> formattedResults = new ArrayList<>();
		String lastResult = null;
		Object selected = state.value(RoutingNode.SELECTED_AGENTS_KEY).orElse(null);
		for (BaseAgent subAgent : subAgents) {
			if (selected instanceof List<?> names && !names.contains(subAgent.name())) {
				continue;
			}
			String outputKey = subAgent.getOutputKey();
			if (outputKey == null) {
				continue;
			}
			Optional<Object> outputOpt = state.value(outputKey);
			if (outputOpt.isPresent()) {
				String text = extractText(outputOpt.get(), outputKey);
				if (text != null && !text.isBlank()) {
					String source = capitalize(subAgent.name());
					formattedResults.add("**From " + source + ":**\n" + text);
					lastResult = text;
					logger.debug("Collected result from {} (key: {})", subAgent.name(), outputKey);
				}
			}
		}

		// When the router delegated to a single sub-agent, that agent's answer is already
		// the
		// final response. Re-synthesizing it through the LLM would issue a redundant
		// model call
		// and emit a second, rephrased copy of the same answer to the user (gh-4616).
		// Pass the
		// single result through unchanged; only genuinely multi-source results need
		// synthesis.
		if (formattedResults.size() == 1) {
			logger.debug("RoutingMergeNode: single routed result, returning it without re-synthesis");
			return Map.of(mergedOutputKey, lastResult);
		}

		String query = extractOriginalQuery(state);
		String finalAnswer = synthesize(query, formattedResults);
		logger.debug("RoutingMergeNode: synthesized {} sources into merged result", formattedResults.size());

		return Map.of(mergedOutputKey, finalAnswer);
	}

	private String extractOriginalQuery(OverAllState state) {
		return state.<String>value(RoutingNode.ORIGINAL_QUERY_KEY)
			.orElseGet(() -> RoutingMessages.originalQuery(RoutingMessages.read(state)));
	}

	private String synthesize(String query, List<String> formattedResults) throws Exception {
		if (formattedResults == null || formattedResults.isEmpty()) {
			return "No results found from any knowledge source.";
		}
		String formatted = String.join("\n\n", formattedResults);
		String systemPrompt = SYNTHESIZE_SYSTEM_TEMPLATE.formatted(query);
		CompletableFuture<io.github.agentic.ai.api.chat.ChatResponse> pending = chatClient
			.call(ChatRequest.of(List.of(ChatMessage.system(systemPrompt), ChatMessage.user(formatted))))
			.toCompletableFuture();
		try {
			return pending.get().text();
		}
		catch (InterruptedException interrupted) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			throw interrupted;
		}
	}

	public static String extractText(Object output, String outputKey) {
		if (output instanceof ChatMessage message) {
			return message.text();
		}
		if (output instanceof io.github.agentic.ai.api.chat.ChatResponse response) {
			return response.text();
		}
		if (output instanceof ChatMessage message) {
			return message.text();
		}
		if (output instanceof GraphResponse<?> gr) {
			Optional<?> val = gr.resultValue();
			if (val.isPresent()) {
				Object v = val.get();
				if (v instanceof Map<?, ?> map) {
					v = map.get(outputKey);
				}
				return extractText(v, outputKey);
			}
			return "";
		}
		return output != null ? output.toString() : "";
	}

	private static String capitalize(String s) {
		if (s == null || s.isEmpty())
			return s;
		return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
	}

}
