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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatResponse;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FluxErrorHandlingTest {

	@Test
	public void testEmptyFluxShouldNotCauseInfiniteLoop() throws Exception {
		StateGraph workflow = new StateGraph();

		workflow.addNode("errorNode", state -> {
			Flux<ChatResponse> errorFlux = Flux.error(new RuntimeException("intentional failure"));
			Map<String, Object> result = new HashMap<>();
			result.put("output", errorFlux);
			return CompletableFuture.completedFuture(result);
		});

		workflow.addEdge(START, "errorNode");
		workflow.addEdge("errorNode", END);

		var app = workflow.compile();

		RuntimeException error = assertThrows(RuntimeException.class,
				() -> app.stream(Map.of()).blockLast(Duration.ofSeconds(2)));
		assertThat(error).hasMessageContaining("intentional failure");
	}

	@Test
	public void testValidFluxShouldWorkNormally() throws Exception {
		StateGraph workflow = new StateGraph();

		workflow.addNode("normalNode", state -> {
			Flux<ChatResponse> validFlux = Flux.just(new ChatResponse(null, null,
					List.of(ChatMessage.assistant("Hello")), List.of(), null, null, Map.of()));
			Map<String, Object> result = new HashMap<>();
			result.put("messages", validFlux);
			return CompletableFuture.completedFuture(result);
		});

		workflow.addEdge(START, "normalNode");
		workflow.addEdge("normalNode", END);

		var app = workflow.compile();

		AtomicInteger responseCount = new AtomicInteger(0);

		app.stream(Map.of()).doOnNext(response -> responseCount.incrementAndGet()).blockLast(Duration.ofSeconds(2));

		assertTrue(responseCount.get() > 0, "Should receive responses");
	}

}
