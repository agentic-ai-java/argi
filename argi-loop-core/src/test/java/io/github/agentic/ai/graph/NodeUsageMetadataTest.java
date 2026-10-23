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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.Usage;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeUsageMetadataTest {

	private static GraphRunnerContext context() throws Exception {
		return new GraphRunnerContext(new OverAllState(), RunnableConfig.builder().build(),
				new StateGraph()
					.addNode("model", state -> java.util.concurrent.CompletableFuture.completedFuture(Map.of()))
					.addEdge(StateGraph.START, "model")
					.addEdge("model", StateGraph.END)
					.compile());
	}

	@Test
	void extractsUsageBeforeApplyingApplicationState() throws Exception {
		GraphRunnerContext context = context();
		Usage usage = new Usage(7L, null, null);
		context.setCurrentNodeId("model");
		context.mergeIntoCurrentState(Map.of(NodeOutput.TOKEN_USAGE_KEY, usage, "answer", "ok"));

		assertEquals(usage, context.buildNodeOutput("model", Map.of(), false).tokenUsage());
		assertEquals(Map.of("answer", "ok"), context.getCurrentStateData());
		context.setCurrentNodeId("tool");
		assertEquals(Usage.unknown(), context.buildNodeOutput("tool", Map.of(), false).tokenUsage());
	}

	@Test
	void rejectsInvalidUsageWithoutCommittingState() throws Exception {
		for (Object invalid : new Object[] { "7", 7L, null }) {
			GraphRunnerContext context = context();
			Map<String, Object> delta = new HashMap<>();
			delta.put(NodeOutput.TOKEN_USAGE_KEY, invalid);
			delta.put("answer", "must not commit");
			assertThrows(IllegalArgumentException.class, () -> context.mergeIntoCurrentState(delta));
			assertFalse(context.getCurrentStateData().containsKey("answer"));
		}
	}

}
