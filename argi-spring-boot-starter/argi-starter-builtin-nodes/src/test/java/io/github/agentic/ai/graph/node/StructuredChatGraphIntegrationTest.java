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
package io.github.agentic.ai.graph.node;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static io.github.agentic.ai.graph.node.QuestionClassifierNodeTest.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredChatGraphIntegrationTest {

	@TempDir
	Path folder;

	private static Map<String, KeyStrategy> strategies() {
		Map<String, KeyStrategy> keys = new HashMap<>();
		keys.put("messages", new AppendStrategy());
		for (String key : List.of("input", "category", "data", "reason", "is_success")) {
			keys.put(key, new ReplaceStrategy());
		}
		return keys;
	}

	@Test
	void resumesStructuredWorkflowFromDiskWithoutRepeatingClassification() throws Exception {
		ChatClient client = mock(ChatClient.class);
		var classified = response("{\"category_name\":\"Person\",\"keywords\":[\"name\"]}");
		var parsed = response("{\"is_success\":true,\"data\":{\"name\":\"Alex\"},\"reason\":\"success\"}");
		when(client.call(any())).thenReturn(CompletableFuture.completedFuture(classified),
				CompletableFuture.completedFuture(parsed));
		var classifier = QuestionClassifierNode.builder()
			.chatClient(client)
			.inputTextKey("input")
			.categories(Map.of("person", "Person"))
			.outputKey("category")
			.build();
		var parser = ParameterParsingNode.builder()
			.chatClient(client)
			.inputTextKey("input")
			.parameters(List.of(ParameterParsingNode.param("name", "String", "Person name")))
			.build();
		StateGraph definition = new StateGraph(StructuredChatGraphIntegrationTest::strategies)
			.addNode("classify", classifier)
			.addNode("parse", parser)
			.addEdge(START, "classify")
			.addConditionalEdges("classify",
					state -> CompletableFuture.completedFuture(state.value("category").orElseThrow().toString()),
					Map.of("person", "parse"))
			.addEdge("parse", END);
		var saver = FileSystemSaver.builder().targetFolder(folder).build();
		var config = RunnableConfig.builder().threadId("structured-workflow").build();
		var first = definition.compile(CompileConfig.builder()
			.saverConfig(SaverConfig.builder().register(saver).build())
			.interruptAfter("classify")
			.build());
		List<NodeOutput> outputs = first
			.stream(Map.of("input", "My name is Alex", "messages", List.of(ChatMessage.user("My name is Alex"))),
					config)
			.collectList()
			.block(Duration.ofSeconds(5));
		assertTrue(outputs.stream()
			.anyMatch(output -> "classify".equals(output.node()) && classified.usage().equals(output.tokenUsage())));
		verify(client, times(1)).call(any());
		var restartedSaver = FileSystemSaver.builder().targetFolder(folder).build();
		Map<String, Object> persisted = restartedSaver.get(config).orElseThrow().getState();
		assertEquals("person", persisted.get("category"));
		assertEquals(List.of(ChatMessage.user("My name is Alex"), classified.messages().get(0)),
				persisted.get("messages"));
		assertFalse(persisted.containsKey(NodeOutput.TOKEN_USAGE_KEY));
		var restarted = definition.compile(
				CompileConfig.builder().saverConfig(SaverConfig.builder().register(restartedSaver).build()).build());
		var resumed = restarted
			.invoke(Map.of(), RunnableConfig.builder().threadId("structured-workflow").resume().build())
			.orElseThrow();
		assertEquals(Map.of("name", "Alex"), resumed.value("data").orElseThrow());
		assertEquals(true, resumed.value("is_success").orElseThrow());
		assertFalse(resumed.data().containsKey(NodeOutput.TOKEN_USAGE_KEY));
		verify(client, times(2)).call(any());
	}

	@Test
	void invalidClassificationDoesNotRunSuccessors() throws Exception {
		ChatClient client = mock(ChatClient.class);
		when(client.call(any()))
			.thenReturn(CompletableFuture.completedFuture(response("{\"category_name\":\"Wrong\"}")));
		var classifier = QuestionClassifierNode.builder()
			.chatClient(client)
			.inputTextKey("input")
			.categories(Map.of("a", "Allowed"))
			.outputKey("category")
			.build();
		AtomicInteger successors = new AtomicInteger();
		var graph = new StateGraph(StructuredChatGraphIntegrationTest::strategies).addNode("classify", classifier)
			.addNode("next", node_async(state -> {
				successors.incrementAndGet();
				return Map.of();
			}))
			.addEdge(START, "classify")
			.addEdge("classify", "next")
			.addEdge("next", END)
			.compile();
		var results = graph.graphResponseStream(Map.of("input", "text"), RunnableConfig.builder().build())
			.collectList()
			.block(Duration.ofSeconds(5));
		assertTrue(results.stream().anyMatch(result -> result.isError()));
		assertEquals(0, successors.get());
	}

}
