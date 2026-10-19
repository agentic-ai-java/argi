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

import io.github.agentic.ai.graph.agent.interceptor.authorization.AuthorizationDecision;
import io.github.agentic.ai.graph.agent.interceptor.authorization.ToolAuthorizationPolicy;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.util.Objects;
import java.util.function.Function;

/**
 * Trusted access filter applied by {@link ToolCatalog} before a summary is returned and
 * again before a schema is released.
 *
 * <p>
 * The policy is consulted on every search and every schema request; the catalog never
 * caches an allow decision. A policy that throws is treated as a denial. A positive
 * decision only makes a tool visible or loadable; it is not an execution grant.
 * </p>
 */
@FunctionalInterface
public interface ToolCatalogAccessPolicy {

	/**
	 * Decides whether the caller may perform {@code access} on {@code tool}.
	 * @param access the catalog operation
	 * @param tool the summary of the tool being accessed
	 * @param context the trusted execution context; {@code null} only when the catalog
	 * does not require one
	 * @return the decision, never {@code null}
	 */
	AuthorizationDecision authorize(ToolCatalogAccess access, ToolSummary tool, TrustedExecutionContext context);

	/**
	 * Allows every catalog operation. Intended for tests and trusted single-tenant
	 * setups only.
	 * @return an allow-all policy
	 */
	static ToolCatalogAccessPolicy allowAll() {
		return (access, tool, context) -> AuthorizationDecision.allow();
	}

	/**
	 * Denies every catalog operation.
	 * @return a deny-all policy
	 */
	static ToolCatalogAccessPolicy denyAll() {
		return (access, tool, context) -> AuthorizationDecision.deny("Tool catalog access is disabled by policy");
	}

	/**
	 * Reuses an execution {@link ToolAuthorizationPolicy} for both catalog operations,
	 * keyed by the tool's {@link ToolId#qualifiedName() qualified name}. The execution
	 * boundary still evaluates the same policy independently when the tool runs.
	 * @param policy the execution authorization policy
	 * @return a catalog access policy delegating to {@code policy}
	 */
	static ToolCatalogAccessPolicy fromToolAuthorizationPolicy(ToolAuthorizationPolicy policy) {
		Objects.requireNonNull(policy, "policy must not be null");
		return (access, tool, context) -> policy.authorize(tool.id().qualifiedName(), context);
	}

	/**
	 * Requires the trusted {@link io.github.agentic.ai.graph.execution.DelegationScope}
	 * to contain the permission derived from each tool. Tools whose derived permission is
	 * {@code null} are denied.
	 * @param permission maps a tool summary to the permission it requires
	 * @return a delegation-scope based policy
	 */
	static ToolCatalogAccessPolicy requirePermission(Function<ToolSummary, String> permission) {
		Objects.requireNonNull(permission, "permission must not be null");
		return (access, tool, context) -> {
			String required = permission.apply(tool);
			if (context == null || required == null || !context.delegationScope().allows(required)) {
				return AuthorizationDecision.deny("Missing tool catalog permission");
			}
			return AuthorizationDecision.allow();
		};
	}

}
