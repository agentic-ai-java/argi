/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import io.github.agentic.ai.graph.execution.TrustedExecutionContextReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContextResolver;

/** Executes the shared identity contract with only its jar and the JDK available. */
public final class RuntimeApiConsumer {

	private RuntimeApiConsumer() {
	}

	public static void main(String[] args) throws Exception {
		for (String unavailable : Set.of("io.github.agentic.ai.graph.StateGraph",
			"io.github.agentic.ai.graph.RunnableConfig", "io.github.agentic.ai.graph.agent.Agent",
			"io.github.agentic.ai.api.chat.ChatClient", "org.springframework.context.ApplicationContext",
			"reactor.core.publisher.Flux", "com.fasterxml.jackson.databind.ObjectMapper")) {
			try {
				Class.forName(unavailable, false, RuntimeApiConsumer.class.getClassLoader());
				throw new IllegalStateException("Unexpected runtime dependency: " + unavailable);
			}
			catch (ClassNotFoundException expected) {
				// A standalone runtime contract must work with these engines and libraries absent.
			}
		}

		TrustedExecutionContext parent = TrustedExecutionContext.builder()
			.tenant(new TenantReference("application-iam", "tenant"))
			.principal(new PrincipalReference("application-iam", "principal"))
			.runId("parent").correlationId("correlation").deadline(Instant.parse("2030-01-01T00:00:00Z"))
			.budget(ExecutionBudget.of(Map.of("model_calls", 3L)))
			.delegationScope(DelegationScope.of(Set.of("agents:delegate", "tools:read"))).build();
		TrustedExecutionContext child = parent.delegate("child", Set.of("tools:read"), null,
			ExecutionBudget.of(Map.of("model_calls", 1L)));
		if (!parent.tenant().equals(child.tenant()) || !parent.deadline().equals(child.deadline())
				|| child.budget().limits().get("model_calls") != 1L
				|| child.delegationScope().allows("agents:delegate")) {
			throw new IllegalStateException("Child execution did not preserve and narrow its parent constraints");
		}
		TrustedExecutionContextReference reference = child.toReference();
		TrustedExecutionContextResolver resolver = candidate -> candidate.equals(reference)
			? Optional.of(child) : Optional.empty();
		if (resolver.resolveRequired(reference) != child || reference.schemaVersion() != 1) {
			throw new IllegalStateException("Reference resolution changed the execution identity");
		}
		try {
			child.budget().limits().put("model_calls", 100L);
			throw new IllegalStateException("Execution constraints must be immutable");
		}
		catch (UnsupportedOperationException expected) {
			// A consumer cannot mutate authenticated constraints through collection accessors.
		}
	}

}
