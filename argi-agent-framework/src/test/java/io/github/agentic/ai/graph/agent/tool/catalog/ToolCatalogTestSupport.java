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

package io.github.agentic.ai.graph.agent.tool.catalog;

import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Shared fixtures for the model-independent tool catalog tests. */
final class ToolCatalogTestSupport {

	private ToolCatalogTestSupport() {
	}

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

	/** Grants each tool only when the trusted scope holds {@code tool:<qualifiedName>}. */
	static ToolCatalogAccessPolicy scopePolicy() {
		return ToolCatalogAccessPolicy.requirePermission(summary -> "tool:" + summary.id().qualifiedName());
	}

	static RecordingTool tool(String name, String description) {
		return tool(name, description, "{\"type\":\"object\"}");
	}

	static RecordingTool tool(String name, String description, String schema) {
		return new RecordingTool(new ToolDefinition(name, description, schema), name + "-result");
	}

	static ToolContext toolContext() {
		return new ToolContext(Map.of());
	}

	static final class RecordingTool implements Tool {

		private final ToolDefinition definition;

		private final String result;

		final AtomicReference<ToolCall> lastCall = new AtomicReference<>();

		RecordingTool(ToolDefinition definition, String result) {
			this.definition = definition;
			this.result = result;
		}

		@Override
		public ToolDefinition definition() {
			return this.definition;
		}

		@Override
		public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
			this.lastCall.set(call);
			return CompletableFuture.completedFuture(ToolResult.text(this.result));
		}

	}

	/** Provider whose behavior can be switched to fail. */
	static final class FlakyProvider implements ToolCatalogProvider {

		private final ToolCatalogProvider delegate;

		volatile boolean failSummaries;

		volatile boolean failResolve;

		int summaryCalls;

		FlakyProvider(ToolCatalogProvider delegate) {
			this.delegate = delegate;
		}

		@Override
		public String providerId() {
			return this.delegate.providerId();
		}

		@Override
		public List<ToolSummary> summaries() {
			this.summaryCalls++;
			if (this.failSummaries) {
				throw new IllegalStateException("catalog backend unavailable");
			}
			return this.delegate.summaries();
		}

		@Override
		public java.util.Optional<ToolCatalogEntry> resolve(ToolId id, String version) {
			if (this.failResolve) {
				throw new IllegalStateException("schema backend unavailable");
			}
			return this.delegate.resolve(id, version);
		}

		@Override
		public java.util.Optional<String> revision() {
			return this.delegate.revision();
		}

	}

}
