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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.stream.LLmNodeAction;
import io.github.agentic.ai.graph.stream.ScriptedChatClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTokenUsageTest {

	@Test
	void testUsageOnlyChunkIsNotDropped() throws Exception {
		Usage usage = new Usage(1L, 2L, 3L);
		ChatClient client = new ScriptedChatClient(() -> Flux
			.just(ChatEvent.textDelta("hi"), ChatEvent.usage(usage), ChatEvent.textDelta("!"),
					ChatEvent.response(new ChatResponse(null, null, List.of(ChatMessage.assistant("hi!")), List.of(),
							null, usage, Map.of())))
			.delayElements(Duration.ofMillis(5)));
		CompiledGraph graph = new StateGraph(() -> Map.of("messages", new AppendStrategy()))
			.addNode("llmNode", node_async(new LLmNodeAction(client, "llmNode")))
			.addEdge(START, "llmNode")
			.addEdge("llmNode", END)
			.compile();
		List<NodeOutput> outputs = graph.stream(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test"))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertNotNull(outputs);
		assertTrue(outputs.stream().anyMatch(output -> Long.valueOf(3).equals(output.tokenUsage().totalTokens())),
				"Usage-only events must reach graph consumers");
	}

}
