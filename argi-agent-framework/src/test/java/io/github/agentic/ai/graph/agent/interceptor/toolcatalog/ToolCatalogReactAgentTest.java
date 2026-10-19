/*
 * Copyright 2025-2026 the original author or authors.
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

package io.github.agentic.ai.graph.agent.interceptor.toolcatalog;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.ReactAgent;
import io.github.agentic.ai.graph.agent.interceptor.authorization.AuthorizationDecision;
import io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationInterceptor;
import io.github.agentic.ai.graph.agent.tool.catalog.InMemoryToolCatalogProvider;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalog;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogAccessPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.agentic.ai.graph.agent.interceptor.toolcatalog.ToolCatalogInterceptorTest.trusted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the catalog through a real {@link ReactAgent} with a stub chat client to prove
 * that retrieval feeds the model, while execution is still authorized independently and
 * the pre-existing tool registry keeps working.
 */
class ToolCatalogReactAgentTest {

	private final AtomicInteger refunds = new AtomicInteger();

	private final AtomicInteger resets = new AtomicInteger();

	private ToolCatalog catalog() {
		return ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments")
				.tool(counting("refund", "Refund an order", this.refunds), "1")
				.tool(counting("admin_reset", "Reset every order", this.resets), "1")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.requirePermission(summary -> "tool:" + summary.id().qualifiedName()))
			.build();
	}

	private static Tool counting(String name, String description, AtomicInteger counter) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, description, "{\"type\":\"object\"}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				counter.incrementAndGet();
				return CompletableFuture.completedFuture(ToolResult.text(name + " executed"));
			}
		};
	}

	/** Stub model: first turn calls {@code toolName}, second turn echoes the tool result. */
	private static ChatClient model(String toolName, List<List<String>> offeredPerTurn) {
		ChatClient client = mock(ChatClient.class);
		when(client.callBlocking(any())).thenCallRealMethod();
		when(client.call(any())).thenAnswer(invocation -> {
			ChatRequest request = invocation.getArgument(0);
			offeredPerTurn.add(request.tools().stream().map(ToolDefinition::name).toList());
			var result = request.messages().stream().filter(message -> message.role() == ChatRole.TOOL).findFirst();
			List<ToolCall> calls = result.isPresent() ? List.of() : List.of(new ToolCall("call-1", toolName, "{}"));
			String text = result.map(ChatMessage::text).orElse("");
			return CompletableFuture
				.completedFuture(new ChatResponse(null, null, List.of(ChatMessage.assistant(text)), calls, null, null,
						Map.of()));
		});
		return client;
	}

	private static RunnableConfig config(String... permissions) {
		return RunnableConfig.builder().threadId("t-" + permissions.length).trustedExecutionContext(trusted(permissions))
			.build();
	}

	@Test
	void retrievedCatalogToolIsOfferedUnderItsQualifiedNameAndExecutes() throws Exception {
		List<List<String>> offered = Collections.synchronizedList(new ArrayList<>());
		ReactAgent agent = ReactAgent.builder()
			.name("catalog-agent")
			.chatClient(model("payments__refund", offered))
			.toolRegistry(new ToolRegistry(List.of(counting("lookup", "Look up an order", new AtomicInteger()))))
			.interceptors(ToolCatalogInterceptor.builder().catalog(catalog()).build(),
					new ToolAuthorizationInterceptor((tool, context) -> AuthorizationDecision.allow()))
			.build();

		ChatMessage answer = agent.call("refund my order", config("tool:payments__refund"));

		assertThat(answer.text()).contains("refund executed");
		assertThat(this.refunds).hasValue(1);
		// Unauthorized catalog tools never reach the model; legacy registry tools still do.
		assertThat(offered.get(0)).containsExactly("lookup", "payments__refund");
	}

	@Test
	void retrievalIsNotAnExecutionGrant() throws Exception {
		List<List<String>> offered = Collections.synchronizedList(new ArrayList<>());
		ReactAgent agent = ReactAgent.builder()
			.name("catalog-agent-denied")
			.chatClient(model("payments__refund", offered))
			.interceptors(ToolCatalogInterceptor.builder().catalog(catalog()).build(),
					new ToolAuthorizationInterceptor((tool, context) -> AuthorizationDecision.deny("execution blocked")))
			.build();

		ChatMessage answer = agent.call("refund my order", config("tool:payments__refund"));

		assertThat(offered.get(0)).containsExactly("payments__refund");
		assertThat(answer.text()).contains("Unauthorized");
		assertThat(this.refunds).hasValue(0);
	}

	@Test
	void aGuessedUndisclosedCatalogToolCannotExecute() throws Exception {
		List<List<String>> offered = Collections.synchronizedList(new ArrayList<>());
		ReactAgent agent = ReactAgent.builder()
			.name("catalog-agent-guess")
			.chatClient(model("payments__admin_reset", offered))
			.interceptors(ToolCatalogInterceptor.builder().catalog(catalog()).build())
			.build();

		ChatMessage answer = agent.call("reset every order", config("tool:payments__refund"));

		assertThat(offered.get(0)).doesNotContain("payments__admin_reset");
		assertThat(answer.text()).contains("Tool not available: payments__admin_reset");
		assertThat(this.resets).hasValue(0);
	}

}
