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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.tool.catalog.InMemoryToolCatalogProvider;
import io.github.agentic.ai.graph.agent.tool.catalog.OfferedTool;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalog;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogAccessPolicy;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogEntry;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogProvider;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolCatalogSearchStatus;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolId;
import io.github.agentic.ai.graph.agent.tool.catalog.ToolSummary;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Model-independent contract tests for {@link ToolCatalogInterceptor}. */
class ToolCatalogInterceptorTest {

	static TrustedExecutionContext trusted(String... permissions) {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("test-iam", "tenant-a"))
			.principal(new PrincipalReference("test-iam", "user-a"))
			.runId("run-1")
			.correlationId("corr-1")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.of(Set.of(permissions)))
			.build();
	}

	static Tool tool(String name, String description) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, description, "{\"type\":\"object\"}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text(name + ":" + call.name()));
			}
		};
	}

	static ToolCatalog catalog() {
		return ToolCatalog.builder()
			.provider(InMemoryToolCatalogProvider.builder("payments")
				.tool(tool("refund", "Refund an order"), "1")
				.tool(tool("charge", "Charge an order"), "1")
				.tool(tool("admin_reset", "Reset every order"), "1")
				.build())
			.accessPolicy(ToolCatalogAccessPolicy.requirePermission(summary -> "tool:" + summary.id().qualifiedName()))
			.build();
	}

	private static ModelRequest.Builder request(String userText, TrustedExecutionContext context) {
		return ModelRequest.builder()
			.messages(List.of(ChatMessage.user(userText)))
			.toolRegistry(new ToolRegistry(List.of(tool("lookup", "Look up an order"))))
			.toolDescriptions(Map.of("lookup", "Look up an order"))
			.trustedExecutionContext(context);
	}

	private static ModelRequest intercept(ToolCatalogInterceptor interceptor, ModelRequest request) {
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, forwarded -> {
			captured.set(forwarded);
			return ModelResponse.of(ChatMessage.assistant("done"));
		});
		return captured.get();
	}

	private static List<String> dynamicNames(ModelRequest request) {
		return request.getDynamicToolRegistry().definitions().stream().map(ToolDefinition::name).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<OfferedTool> offered(ModelRequest request) {
		return (List<OfferedTool>) request.getContext().get(ToolCatalogInterceptor.OFFERED_TOOLS_CONTEXT_KEY);
	}

	private static Object status(ModelRequest request) {
		return request.getContext().get(ToolCatalogInterceptor.SEARCH_STATUS_CONTEXT_KEY);
	}

	@Test
	void disclosesOnlyAuthorizedMatchesAndRecordsTheOfferedSnapshot() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).build();

		ModelRequest forwarded = intercept(interceptor,
				request("please refund my order", trusted("tool:payments__refund")).build());

		assertThat(dynamicNames(forwarded)).containsExactly("payments__refund");
		assertThat(forwarded.getTools()).containsExactly("lookup", "payments__refund");
		assertThat(forwarded.getToolDescriptions()).containsEntry("payments__refund", "Refund an order")
			.containsEntry("lookup", "Look up an order");
		assertThat(status(forwarded)).isEqualTo(ToolCatalogSearchStatus.MATCHED);
		assertThat(offered(forwarded)).singleElement().satisfies(offer -> {
			assertThat(offer.id()).isEqualTo(ToolId.of("payments", "refund"));
			assertThat(offer.version()).isEqualTo("1");
		});
		assertThat(forwarded.getTrustedExecutionContext()).isPresent();
	}

	@Test
	void missingTrustedContextDisclosesNoCatalogToolAndKeepsExistingTools() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).build();

		ModelRequest forwarded = intercept(interceptor, request("refund my order", null).build());

		assertThat(dynamicNames(forwarded)).isEmpty();
		assertThat(forwarded.getTools()).containsExactly("lookup");
		assertThat(forwarded.hasToolSelection()).isFalse();
		assertThat(status(forwarded)).isEqualTo(ToolCatalogSearchStatus.UNAUTHENTICATED);
		assertThat(offered(forwarded)).isEmpty();
	}

	@Test
	void noMatchDisclosesNothing() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).build();

		ModelRequest forwarded = intercept(interceptor,
				request("what is the weather", trusted("tool:payments__refund")).build());

		assertThat(dynamicNames(forwarded)).isEmpty();
		assertThat(forwarded.getTools()).containsExactly("lookup");
		assertThat(status(forwarded)).isEqualTo(ToolCatalogSearchStatus.EMPTY);
	}

	@Test
	void catalogFailureNeverFallsBackToTheWholeCatalog() {
		ToolCatalogProvider broken = new ToolCatalogProvider() {
			@Override
			public String providerId() {
				return "payments";
			}

			@Override
			public List<ToolSummary> summaries() {
				throw new IllegalStateException("catalog backend down");
			}

			@Override
			public Optional<ToolCatalogEntry> resolve(ToolId id, String version) {
				throw new IllegalStateException("catalog backend down");
			}
		};
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder()
			.catalog(ToolCatalog.builder().provider(broken).accessPolicy(ToolCatalogAccessPolicy.allowAll()).build())
			.build();

		ModelRequest forwarded = intercept(interceptor, request("refund", trusted()).build());

		assertThat(dynamicNames(forwarded)).isEmpty();
		assertThat(forwarded.getTools()).containsExactly("lookup");
		assertThat(status(forwarded)).isEqualTo(ToolCatalogSearchStatus.FAILED);
	}

	@Test
	void queryDerivationFailureDisclosesNothing() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).queryText(request -> {
			throw new IllegalStateException("bad query");
		}).build();

		ModelRequest forwarded = intercept(interceptor, request("refund", trusted("tool:payments__refund")).build());

		assertThat(dynamicNames(forwarded)).isEmpty();
		assertThat(status(forwarded)).isEqualTo(ToolCatalogSearchStatus.FAILED);
	}

	@Test
	void disclosureIsBounded() {
		InMemoryToolCatalogProvider.Builder provider = InMemoryToolCatalogProvider.builder("bulk");
		for (int i = 0; i < 12; i++) {
			provider.tool(tool("order_tool_" + i, "Order helper " + i), "1");
		}
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder()
			.catalog(ToolCatalog.builder()
				.provider(provider.build())
				.accessPolicy(ToolCatalogAccessPolicy.allowAll())
				.build())
			.maxTools(2)
			.build();

		ModelRequest forwarded = intercept(interceptor, request("order", trusted()).build());

		assertThat(dynamicNames(forwarded)).hasSize(2);
		assertThat(offered(forwarded)).hasSize(2);
	}

	@Test
	void extendsAnExplicitSelectionButRespectsAnExplicitlyEmptyOne() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).build();

		ModelRequest narrowed = intercept(interceptor,
				request("refund", trusted("tool:payments__refund")).tools(List.of("lookup")).build());
		assertThat(narrowed.getTools()).containsExactly("lookup", "payments__refund");

		ModelRequest disabled = intercept(interceptor,
				request("refund", trusted("tool:payments__refund")).tools(List.of()).build());
		assertThat(disabled.getTools()).isEmpty();
		assertThat(dynamicNames(disabled)).isEmpty();
	}

	@Test
	void existingBindingsAreNeverShadowed() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).build();
		ModelRequest request = request("refund", trusted("tool:payments__refund"))
			.toolRegistry(new ToolRegistry(List.of(tool("payments__refund", "Application owned refund"))))
			.build();

		ModelRequest forwarded = intercept(interceptor, request);

		assertThat(dynamicNames(forwarded)).isEmpty();
		assertThat(offered(forwarded)).isEmpty();
		assertThat(status(forwarded)).isEqualTo(ToolCatalogSearchStatus.EMPTY);
	}

	@Test
	void appendsToExistingDynamicDisclosures() {
		ToolCatalogInterceptor interceptor = ToolCatalogInterceptor.builder().catalog(catalog()).build();
		ModelRequest request = request("refund", trusted("tool:payments__refund"))
			.dynamicToolRegistry(new ToolRegistry(List.of(tool("skill_tool", "Skill provided"))))
			.build();

		assertThat(dynamicNames(intercept(interceptor, request))).containsExactly("skill_tool", "payments__refund");
	}

	@Test
	void catalogToolsAreNeverContributedAsStaticTools() {
		assertThat(ToolCatalogInterceptor.builder().catalog(catalog()).build().getTools()).isEmpty();
	}

}
