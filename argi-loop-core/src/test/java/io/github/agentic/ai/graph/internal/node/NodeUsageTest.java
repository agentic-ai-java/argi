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
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeUsageTest {

	@Test
	void sumsLongCountsWithoutApplyingApplicationStrategiesToMetadata() {
		Map<String, Object> left = Map.of(NodeOutput.TOKEN_USAGE_KEY, new Usage(3_000_000_000L, 2L, 3_000_000_002L),
				"messages", List.of("left"));
		Map<String, Object> right = Map.of(NodeOutput.TOKEN_USAGE_KEY, new Usage(4L, 5L, 9L), "messages",
				List.of("right"));
		var merged = NodeUsage.merge(left, right,
				Map.of("messages", new AppendStrategy(), NodeOutput.TOKEN_USAGE_KEY, new AppendStrategy()));
		assertEquals(new Usage(3_000_000_004L, 7L, 3_000_000_011L), merged.get(NodeOutput.TOKEN_USAGE_KEY));
		assertEquals(List.of("left", "right"), merged.get("messages"));
		assertEquals(List.of("left"), left.get("messages"));
	}

	@Test
	void preservesUnknownFieldsAndIgnoresBranchesWithoutUsage() {
		Map<String, Object> partial = Map.of(NodeOutput.TOKEN_USAGE_KEY, new Usage(3L, null, null));
		assertEquals(partial, NodeUsage.merge(Map.of(), partial, Map.of()));
		assertEquals(partial, NodeUsage.merge(partial, Map.of(), Map.of()));
		var merged = NodeUsage.merge(partial, Map.of(NodeOutput.TOKEN_USAGE_KEY, new Usage(4L, 5L, 9L)), Map.of());
		assertEquals(new Usage(7L, null, null), merged.get(NodeOutput.TOKEN_USAGE_KEY));
		assertEquals(Usage.unknown(),
				NodeUsage.merge(partial, Map.of(NodeOutput.TOKEN_USAGE_KEY, Usage.unknown()), Map.of())
					.get(NodeOutput.TOKEN_USAGE_KEY));
		assertFalse(
				NodeUsage.merge(Map.of(), Map.of("answer", "ok"), Map.of()).containsKey(NodeOutput.TOKEN_USAGE_KEY));
	}

	@Test
	void rejectsInvalidUsageInEitherDeltaBeforeInvokingStrategies() {
		for (Object invalid : new Object[] { null, "bad", 1L }) {
			Map<String, Object> delta = new HashMap<>();
			delta.put(NodeOutput.TOKEN_USAGE_KEY, invalid);
			assertThrows(IllegalArgumentException.class, () -> NodeUsage.merge(Map.of(), delta, Map.of()));
			assertThrows(IllegalArgumentException.class, () -> NodeUsage.merge(delta, Map.of(), Map.of()));
		}
	}

	@Test
	void rejectsOverflowInsteadOfWrappingCounts() {
		assertThrows(ArithmeticException.class,
				() -> NodeUsage.merge(Map.of(NodeOutput.TOKEN_USAGE_KEY, new Usage(Long.MAX_VALUE, 0L, 0L)),
						Map.of(NodeOutput.TOKEN_USAGE_KEY, new Usage(1L, 0L, 0L)), Map.of()));
	}

}
