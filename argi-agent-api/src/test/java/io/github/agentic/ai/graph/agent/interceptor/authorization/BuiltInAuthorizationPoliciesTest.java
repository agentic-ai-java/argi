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

package io.github.agentic.ai.graph.agent.interceptor.authorization;

import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for built-in authorization policy factory methods in
 * {@link ToolAuthorizationPolicy}.
 */
class BuiltInAuthorizationPoliciesTest {

	@Test
	void allowAll_shouldAlwaysAllow() {
		ToolAuthorizationPolicy policy = ToolAuthorizationPolicy.allowAll();

		// No context
		AuthorizationDecision decision1 = policy.authorize("test-tool", null);
		assertThat(decision1.allowed()).isTrue();

		// With context
		TrustedExecutionContext ctx = minimalContext();
		AuthorizationDecision decision2 = policy.authorize("any-tool", ctx);
		assertThat(decision2.allowed()).isTrue();
	}

	@Test
	void denyAll_shouldAlwaysDeny() {
		ToolAuthorizationPolicy policy = ToolAuthorizationPolicy.denyAll();

		// No context
		AuthorizationDecision decision1 = policy.authorize("test-tool", null);
		assertThat(decision1.allowed()).isFalse();
		assertThat(decision1.reason()).contains("disabled");

		// With context
		TrustedExecutionContext ctx = minimalContext();
		AuthorizationDecision decision2 = policy.authorize("any-tool", ctx);
		assertThat(decision2.allowed()).isFalse();
		assertThat(decision2.reason()).contains("disabled");
	}

	@Test
	void requireMatchingTenant_shouldAllowWhenTenantPresent() {
		ToolAuthorizationPolicy policy = ToolAuthorizationPolicy.requireMatchingTenant();

		TrustedExecutionContext ctx = minimalContext();
		AuthorizationDecision decision = policy.authorize("test-tool", ctx);

		assertThat(decision.allowed()).isTrue();
	}

	@Test
	void requireMatchingTenant_shouldDenyWhenContextIsNull() {
		ToolAuthorizationPolicy policy = ToolAuthorizationPolicy.requireMatchingTenant();

		AuthorizationDecision decision = policy.authorize("test-tool", null);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).contains("trusted tenant identity");
	}

	private static TrustedExecutionContext minimalContext() {
		return TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant-1"))
			.principal(new PrincipalReference("test-auth", "user-1"))
			.runId("run-1")
			.correlationId("corr-1")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
	}

}
