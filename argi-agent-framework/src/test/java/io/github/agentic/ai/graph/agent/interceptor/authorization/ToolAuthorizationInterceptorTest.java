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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallExecutionContext;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuthorizationInterceptorTest {

	@Test
	void shouldRejectNullPolicy() {
		assertThatThrownBy(() -> new ToolAuthorizationInterceptor(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("policy must not be null");
	}

	@Test
	void shouldAllowWhenPolicyAllows() {
		// Given: policy that allows everything
		ToolAuthorizationPolicy policy = (tool, ctx) -> AuthorizationDecision.allow();

		ToolAuthorizationInterceptor interceptor = new ToolAuthorizationInterceptor(policy);

		TrustedExecutionContext trustCtx = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant-1"))
			.principal(new PrincipalReference("test-auth", "user-1"))
			.runId("run-1")
			.correlationId("corr-1")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		RunnableConfig config = RunnableConfig.builder()
			.trustedExecutionContext(trustCtx)
			.build();
		ToolCallExecutionContext execCtx = new ToolCallExecutionContext(config, new OverAllState());

		ToolCallRequest request = ToolCallRequest.builder()
			.toolName("test-tool")
			.toolCallId("call-1")
			.arguments("{}")
			.context(Map.of())
			.executionContext(execCtx)
			.build();

		ToolCallHandler baseHandler = req -> ToolCallResponse.success(req.getToolCallId(), req.getToolName(), "OK");

		// When
		ToolCallResponse response = interceptor.interceptToolCall(request, baseHandler);

		// Then
		assertThat(response.getStatus()).isEqualTo(ToolOutcome.SUCCESS);
		assertThat(response.getResult()).isEqualTo("OK");
	}

	@Test
	void shouldDenyWhenPolicyDenies() {
		// Given: policy that denies everything
		ToolAuthorizationPolicy policy = (tool, ctx) -> AuthorizationDecision.deny("Access denied");

		ToolAuthorizationInterceptor interceptor = new ToolAuthorizationInterceptor(policy);

		TrustedExecutionContext trustCtx = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant-1"))
			.principal(new PrincipalReference("test-auth", "user-1"))
			.runId("run-1")
			.correlationId("corr-1")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		RunnableConfig config = RunnableConfig.builder()
			.trustedExecutionContext(trustCtx)
			.build();
		ToolCallExecutionContext execCtx = new ToolCallExecutionContext(config, new OverAllState());

		ToolCallRequest request = ToolCallRequest.builder()
			.toolName("test-tool")
			.toolCallId("call-1")
			.arguments("{}")
			.context(Map.of())
			.executionContext(execCtx)
			.build();

		ToolCallHandler baseHandler = req -> {
			throw new AssertionError("Base handler should not be called");
		};

		// When
		ToolCallResponse response = interceptor.interceptToolCall(request, baseHandler);

		// Then
		assertThat(response.getStatus()).isEqualTo(ToolOutcome.AUTH_DENIED);
		assertThat(response.getResult()).contains("Unauthorized");
		assertThat(response.getMetadata()).containsEntry("authDenied", true);
	}

	@Test
	void shouldDenyInStrictModeWhenNoTrustedContext() {
		// Given: strict mode interceptor
		ToolAuthorizationPolicy policy = (tool, ctx) -> AuthorizationDecision.allow();
		ToolAuthorizationInterceptor interceptor = new ToolAuthorizationInterceptor(policy, true);

		ToolCallRequest request = ToolCallRequest.builder()
			.toolName("test-tool")
			.toolCallId("call-1")
			.arguments("{}")
			.context(Map.of())
			.build();

		ToolCallHandler baseHandler = req -> {
			throw new AssertionError("Base handler should not be called");
		};

		// When
		ToolCallResponse response = interceptor.interceptToolCall(request, baseHandler);

		// Then
		assertThat(response.getStatus()).isEqualTo(ToolOutcome.AUTH_DENIED);
		assertThat(response.getResult()).contains("Missing trusted execution context");
	}

	@Test
	void shouldCallPolicyInNonStrictModeWhenNoTrustedContext() {
		// Given: non-strict mode interceptor, policy that allows null context
		ToolAuthorizationPolicy policy = (tool, ctx) -> {
			assertThat(ctx).isNull();
			return AuthorizationDecision.allow();
		};

		ToolAuthorizationInterceptor interceptor = new ToolAuthorizationInterceptor(policy, false);

		ToolCallRequest request = ToolCallRequest.builder()
			.toolName("test-tool")
			.toolCallId("call-1")
			.arguments("{}")
			.context(Map.of())
			.build();

		ToolCallHandler baseHandler = req -> ToolCallResponse.success(req.getToolCallId(), req.getToolName(), "OK");

		// When
		ToolCallResponse response = interceptor.interceptToolCall(request, baseHandler);

		// Then
		assertThat(response.getStatus()).isEqualTo(ToolOutcome.SUCCESS);
	}

	@Test
	void shouldRecheckAuthorizationOnRetry() {
		// Given: policy that denies on first call, allows on second
		int[] callCount = {0};
		ToolAuthorizationPolicy policy = (tool, ctx) -> {
			callCount[0]++;
			return callCount[0] == 1 ? AuthorizationDecision.deny("First denied")
					: AuthorizationDecision.allow();
		};

		ToolAuthorizationInterceptor interceptor = new ToolAuthorizationInterceptor(policy);

		TrustedExecutionContext trustCtx = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant-1"))
			.principal(new PrincipalReference("test-auth", "user-1"))
			.runId("run-1")
			.correlationId("corr-1")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		RunnableConfig config = RunnableConfig.builder()
			.trustedExecutionContext(trustCtx)
			.build();
		ToolCallExecutionContext execCtx = new ToolCallExecutionContext(config, new OverAllState());

		ToolCallRequest request = ToolCallRequest.builder()
			.toolName("test-tool")
			.toolCallId("call-1")
			.arguments("{}")
			.context(Map.of())
			.executionContext(execCtx)
			.build();

		ToolCallHandler baseHandler = req -> ToolCallResponse.success(req.getToolCallId(), req.getToolName(), "OK");

		// When: first call
		ToolCallResponse response1 = interceptor.interceptToolCall(request, baseHandler);

		// Then: denied
		assertThat(response1.getStatus()).isEqualTo(ToolOutcome.AUTH_DENIED);

		// When: second call (simulating retry)
		ToolCallResponse response2 = interceptor.interceptToolCall(request, baseHandler);

		// Then: allowed
		assertThat(response2.getStatus()).isEqualTo(ToolOutcome.SUCCESS);
		assertThat(callCount[0]).isEqualTo(2);
	}

	@Test
	void shouldPassTenantToPolicy() {
		// Given: policy that inspects tenant
		ToolAuthorizationPolicy policy = (tool, ctx) -> {
			assertThat(ctx).isNotNull();
			assertThat(ctx.tenant()).isNotNull();
			assertThat(ctx.tenant().id()).isEqualTo("tenant-42");
			return AuthorizationDecision.allow();
		};

		ToolAuthorizationInterceptor interceptor = new ToolAuthorizationInterceptor(policy);

		TrustedExecutionContext trustCtx = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant-42"))
			.principal(new PrincipalReference("test-auth", "user-42"))
			.runId("run-42")
			.correlationId("corr-42")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		RunnableConfig config = RunnableConfig.builder()
			.trustedExecutionContext(trustCtx)
			.build();
		ToolCallExecutionContext execCtx = new ToolCallExecutionContext(config, new OverAllState());

		ToolCallRequest request = ToolCallRequest.builder()
			.toolName("test-tool")
			.toolCallId("call-1")
			.arguments("{}")
			.context(Map.of())
			.executionContext(execCtx)
			.build();

		ToolCallHandler baseHandler = req -> ToolCallResponse.success(req.getToolCallId(), req.getToolName(), "OK");

		// When
		ToolCallResponse response = interceptor.interceptToolCall(request, baseHandler);

		// Then
		assertThat(response.getStatus()).isEqualTo(ToolOutcome.SUCCESS);
	}

}
