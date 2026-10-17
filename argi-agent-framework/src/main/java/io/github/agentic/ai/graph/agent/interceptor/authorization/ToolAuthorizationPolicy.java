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

import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

/**
 * Application-level policy for tool authorization.
 *
 * <p>
 * Implementations decide whether a tool call should be allowed based on the tool name
 * and trusted execution context. The framework calls this policy before every tool
 * execution when a {@link ToolAuthorizationInterceptor} is configured.
 *
 * <p>
 * This policy is invoked on every tool call, including retries. Implementations must
 * not cache authorization results across calls; revoking access must take effect
 * immediately.
 *
 * <p>
 * Three built-in implementations are available via static factory methods:
 * <ul>
 *   <li>{@link #allowAll()} — allows every tool call without restriction; suitable for
 *       development, testing, or gradual migration from unprotected code.</li>
 *   <li>{@link #denyAll()} — rejects every tool call; useful for hard-disabling execution
 *       in a specific environment.</li>
 *   <li>{@link #requireMatchingTenant()} — allows a call only when the caller's tenant
 *       matches the tenant of the thread/session; suitable as an out-of-the-box tenant
 *       boundary when a full custom policy is not yet in place.</li>
 * </ul>
 */
public interface ToolAuthorizationPolicy {

	/**
	 * Decide whether a tool call is authorized.
	 * @param toolName the tool being invoked
	 * @param context the trusted execution context; may be null in non-strict mode
	 * @return an authorization decision
	 */
	AuthorizationDecision authorize(String toolName, TrustedExecutionContext context);

	/**
	 * Returns a policy that allows every tool call without restriction.
	 * <p>
	 * Intended for development, testing, or as a compatibility shim when adopting
	 * authorization incrementally. Do not use in production without understanding that
	 * all authorization checks are bypassed.
	 * @return an allow-all policy
	 */
	static ToolAuthorizationPolicy allowAll() {
		return (toolName, context) -> AuthorizationDecision.allow();
	}

	/**
	 * Returns a policy that denies every tool call.
	 * <p>
	 * Useful for hard-disabling tool execution in a specific environment or as a
	 * safe default when no real policy is configured yet.
	 * @return a deny-all policy
	 */
	static ToolAuthorizationPolicy denyAll() {
		return (toolName, context) -> AuthorizationDecision.deny("Tool execution is disabled by policy");
	}

	/**
	 * Returns a policy that requires the caller's tenant to be present.
	 * <p>
	 * Allows the call when the trusted execution context carries a non-null tenant
	 * reference, and denies it otherwise. This provides a lightweight tenant boundary
	 * without a full custom implementation: every call must arrive with a verifiable
	 * tenant identity, but no cross-tenant comparison is performed here.
	 * <p>
	 * Use this as a starting point and replace it with a fully scoped policy once
	 * per-tool or per-tenant rules are known.
	 * @return a tenant-required policy
	 */
	static ToolAuthorizationPolicy requireMatchingTenant() {
		return (toolName, context) -> {
			if (context == null || context.tenant() == null) {
				return AuthorizationDecision.deny("Tool call requires a trusted tenant identity");
			}
			return AuthorizationDecision.allow();
		};
	}

}
