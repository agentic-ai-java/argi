/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.graph.internal.node;

import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import java.util.HashMap;
import java.util.Map;

/** Merges node deltas without applying application key strategies to usage metadata. */
public final class NodeUsage {

	private NodeUsage() {
	}

	public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> delta,
			Map<String, KeyStrategy> strategies) {
		Usage previous = usage(current);
		Usage incoming = usage(delta);
		Map<String, Object> currentState = new HashMap<>(current);
		Map<String, Object> deltaState = new HashMap<>(delta);
		currentState.remove(NodeOutput.TOKEN_USAGE_KEY);
		deltaState.remove(NodeOutput.TOKEN_USAGE_KEY);
		Map<String, Object> merged = new HashMap<>(OverAllState.updateState(currentState, deltaState, strategies));
		if (previous != null || incoming != null) {
			merged.put(NodeOutput.TOKEN_USAGE_KEY,
					previous == null ? incoming
							: incoming == null ? previous
									: new Usage(sum(previous.inputTokens(), incoming.inputTokens()),
											sum(previous.outputTokens(), incoming.outputTokens()),
											sum(previous.totalTokens(), incoming.totalTokens())));
		}
		return merged;
	}

	private static Usage usage(Map<String, Object> state) {
		if (!state.containsKey(NodeOutput.TOKEN_USAGE_KEY)) {
			return null;
		}
		if (!(state.get(NodeOutput.TOKEN_USAGE_KEY) instanceof Usage usage)) {
			throw new IllegalArgumentException("Node token usage must be an ARGI Usage value");
		}
		return usage;
	}

	private static Long sum(Long left, Long right) {
		// A missing provider count is unknown, not zero or a partial aggregate.
		return left == null || right == null ? null : Math.addExact(left, right);
	}

}
