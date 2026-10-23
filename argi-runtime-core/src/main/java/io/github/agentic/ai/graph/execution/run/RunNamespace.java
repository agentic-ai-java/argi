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

package io.github.agentic.ai.graph.execution.run;

import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import static java.util.Objects.requireNonNull;

/**
 * Tenant/application/session storage scope; runId is deliberately not part of the key.
 */
public record RunNamespace(TenantReference tenant, String applicationId, String sessionId) {

	public RunNamespace {
		requireNonNull(tenant, "tenant cannot be null");
		RunValidation.text(applicationId, "applicationId");
		RunValidation.text(sessionId, "sessionId");
	}

	/**
	 * Call only at an application-owned authentication boundary, not from model metadata.
	 */
	public static RunNamespace from(TrustedExecutionContext context, String applicationId, String sessionId) {
		return new RunNamespace(requireNonNull(context, "context cannot be null").tenant(), applicationId, sessionId);
	}
}
