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
package io.github.agentic.ai.graph.agent.tools;

import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallExecutionContext;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolContextHelperTrustedExecutionTest {

	@Test
	void helperReadsTrustedScopeOnlyThroughFrameworkRunnableConfig() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("example-iam", "tenant-a"))
			.principal(new PrincipalReference("example-iam", "principal-a"))
			.runId("run-a")
			.correlationId("correlation-a")
			.delegationScope(DelegationScope.of(Set.of("tools:read")))
			.build();
		RunnableConfig config = RunnableConfig.builder()
			.addMetadata("tenant_id", "forged")
			.trustedExecutionContext(trusted)
			.build();
		ToolContext toolContext = new ToolContext(
				Map.of(ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY, config, "trusted_execution_context", "forged"));

		assertThat(ToolContextHelper.getTrustedExecutionContext(toolContext)).contains(trusted);
		assertThat(new ToolCallExecutionContext(config, new OverAllState()).trustedExecutionContext())
			.contains(trusted);
		assertThat(ToolContextHelper.getTrustedExecutionContext(
				new ToolContext(Map.of("tenant_id", "forged", "trusted_execution_context", trusted))))
			.isEmpty();
	}

}
