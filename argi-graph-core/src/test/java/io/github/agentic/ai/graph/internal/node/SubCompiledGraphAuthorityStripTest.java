/*
 * Copyright 2025-2026 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.api.chat.ContextAuthorityKeys;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.GraphResponse;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.KeyStrategyFactory;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static org.assertj.core.api.Assertions.assertThat;

class SubCompiledGraphAuthorityStripTest {

	@Test
	void stateWithoutAuthorityDropsProtectedKeysButKeepsOrdinaryState() {
		OverAllState parent = new OverAllState(Map.of("messages", "hello", ContextAuthorityKeys.SECRET_CONFIG,
				"forged-secret", ContextAuthorityKeys.AUTHORIZATION, "forged-auth"));

		OverAllState child = SubCompiledGraphNodeAction.stateWithoutAuthority(parent);

		assertThat(child.data()).containsEntry("messages", "hello");
		assertThat(child.data()).doesNotContainKey(ContextAuthorityKeys.SECRET_CONFIG);
		assertThat(child.data()).doesNotContainKey(ContextAuthorityKeys.AUTHORIZATION);
	}

	@Test
	void subgraphNodesDoNotSeeParentAuthorityKeysInState() throws Exception {
		AtomicReference<Map<String, Object>> seenByChild = new AtomicReference<>();
		KeyStrategyFactory strategies = () -> {
			Map<String, KeyStrategy> map = new HashMap<>();
			map.put("messages", new ReplaceStrategy());
			map.put("seen", new ReplaceStrategy());
			return map;
		};
		CompiledGraph child = new StateGraph(strategies)
			.addNode("capture", AsyncNodeActionWithConfig.node_async((state, config) -> {
				seenByChild.set(Map.copyOf(state.data()));
				return Map.of("seen", "yes");
			}))
			.addEdge(START, "capture")
			.addEdge("capture", END)
			.compile(CompileConfig.builder().build());

		SubCompiledGraphNodeAction action = new SubCompiledGraphNodeAction("nested", CompileConfig.builder().build(),
				child);
		OverAllState parent = new OverAllState(Map.of("messages", "parent", ContextAuthorityKeys.SECRET_CONFIG,
				"must-not-propagate"));
		Map<String, Object> result = action.apply(parent, RunnableConfig.builder().build()).join();
		@SuppressWarnings("unchecked")
		Flux<GraphResponse<NodeOutput>> stream = (Flux<GraphResponse<NodeOutput>>) result
			.get(ResumableSubGraphAction.outputKeyToParent("nested"));
		stream.collectList().block();

		assertThat(seenByChild.get()).isNotNull();
		assertThat(seenByChild.get()).containsEntry("messages", "parent");
		assertThat(seenByChild.get()).doesNotContainKey(ContextAuthorityKeys.SECRET_CONFIG);
	}

}
