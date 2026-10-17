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

import io.github.agentic.ai.graph.agent.interceptor.ToolCallExecutionContext;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.ToolOutcome;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool interceptor that enforces authorization checks before tool execution.
 *
 * <p>
 * This interceptor calls a {@link ToolAuthorizationPolicy} to decide whether a tool
 * call should proceed. Authorization decisions are made on every invocation, including
 * retries, so that revoked permissions take effect immediately.
 *
 * <p>
 * In strict mode (default), a missing trusted execution context results in immediate
 * rejection. In non-strict mode, the policy is called with a null context and may
 * choose to allow the call.
 *
 * <p>
 * When a tool call is denied, no tool callback is invoked and an {@code auth-denied}
 * response is returned.
 */
public class ToolAuthorizationInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(ToolAuthorizationInterceptor.class);

	private final ToolAuthorizationPolicy policy;

	private final boolean strictMode;

	/**
	 * Create an interceptor with strict mode enabled.
	 * @param policy the authorization policy
	 */
	public ToolAuthorizationInterceptor(ToolAuthorizationPolicy policy) {
		this(policy, true);
	}

	/**
	 * Create an interceptor with configurable strict mode.
	 * @param policy the authorization policy
	 * @param strictMode whether to reject calls with no trusted context
	 */
	public ToolAuthorizationInterceptor(ToolAuthorizationPolicy policy, boolean strictMode) {
		if (policy == null) {
			throw new IllegalArgumentException("policy must not be null");
		}
		this.policy = policy;
		this.strictMode = strictMode;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		ToolCallExecutionContext execCtx = request.getExecutionContext().orElse(null);
		TrustedExecutionContext trustCtx = execCtx != null ? execCtx.trustedExecutionContext().orElse(null) : null;

		// Strict mode: reject if no trusted context
		if (strictMode && trustCtx == null) {
			logger.warn("Tool call rejected in strict mode: no trusted execution context (tool={})", request.getToolName());
			return unauthorizedResponse(request, "Missing trusted execution context");
		}

		// Call policy
		AuthorizationDecision decision = policy.authorize(request.getToolName(), trustCtx);

		if (decision.isDenied()) {
			String reason = decision.reason() != null ? decision.reason() : "Access denied";
			logger.warn("Tool call denied by policy (tool={}, reason={})", request.getToolName(), reason);
			return unauthorizedResponse(request, reason);
		}

		// Allow
		return handler.call(request);
	}

	private ToolCallResponse unauthorizedResponse(ToolCallRequest request, String reason) {
		return ToolCallResponse.builder()
			.toolCallId(request.getToolCallId())
			.toolName(request.getToolName())
			.status(ToolOutcome.AUTH_DENIED)
			.content("Unauthorized: " + reason)
			.metadata(Map.of("error", true, "authDenied", true, "reason", reason))
			.build();
	}

	@Override
	public String getName() {
		return "ToolAuthorization";
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private ToolAuthorizationPolicy policy;

		private boolean strictMode = true;

		public Builder policy(ToolAuthorizationPolicy policy) {
			this.policy = policy;
			return this;
		}

		public Builder strictMode(boolean strictMode) {
			this.strictMode = strictMode;
			return this;
		}

		public ToolAuthorizationInterceptor build() {
			if (policy == null) {
				throw new IllegalStateException("policy must be set");
			}
			return new ToolAuthorizationInterceptor(policy, strictMode);
		}

	}

}
