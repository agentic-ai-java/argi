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

package io.github.agentic.ai.graph.observation.trace;

import java.util.Map;

import io.github.agentic.ai.graph.GraphLifecycleListener;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.execution.DelegationScope;
import io.github.agentic.ai.graph.execution.ExecutionBudget;
import io.github.agentic.ai.graph.execution.PrincipalReference;
import io.github.agentic.ai.graph.execution.TenantReference;
import io.github.agentic.ai.graph.execution.TrustedExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentTraceContext capture tests")
class AgentTraceContextTest {

	@Test
	@DisplayName("capture derives tenant, run id, thread id and attempt id from config and state")
	void capturesCorrelationFactsFromConfigAndState() {
		RunnableConfig config = RunnableConfig.builder()
			.threadId("parent-thread")
			.trustedExecutionContext(TrustedExecutionContext.builder()
				.tenant(new TenantReference("example-iam", "tenant-a"))
				.principal(new PrincipalReference("example-iam", "principal-a"))
				.runId("run-1")
				.correlationId("correlation-a")
				.budget(new ExecutionBudget(Map.of("tokens", 100L)))
				.delegationScope(new DelegationScope(java.util.Set.of("agents:delegate")))
				.build())
			.build();
		Map<String, Object> state = Map.of(GraphLifecycleListener.EXECUTION_ID_KEY, "exec-1");

		AgentTraceContext context = AgentTraceContext.capture(config, state);

		assertThat(context.tenant()).isEqualTo("tenant-a");
		assertThat(context.runId()).isEqualTo("run-1");
		assertThat(context.threadId()).isEqualTo("parent-thread");
		assertThat(context.attemptId()).isEqualTo("exec-1");
		assertThat(context.taskId()).isNull();
		assertThat(context.delegationId()).isNull();

		AgentTraceContext enriched = context.withTaskId("task-9").withDelegationId("delegation-9");
		assertThat(enriched.taskId()).isEqualTo("task-9");
		assertThat(enriched.delegationId()).isEqualTo("delegation-9");
		assertThat(enriched.tenant()).isEqualTo("tenant-a");
		assertThat(enriched.runId()).isEqualTo("run-1");
		assertThat(enriched.attemptId()).isEqualTo("exec-1");
	}

	@Test
	@DisplayName("capture tolerates absent config and state")
	void captureToleratesNulls() {
		AgentTraceContext context = AgentTraceContext.capture(null, null);

		assertThat(context.tenant()).isNull();
		assertThat(context.runId()).isNull();
		assertThat(context.threadId()).isNull();
		assertThat(context.attemptId()).isNull();
	}

	@Test
	@DisplayName("capture ignores non-string execution ids and configs without trusted facts")
	void captureIgnoresUnknownFacts() {
		RunnableConfig config = RunnableConfig.builder().threadId("t").build();

		AgentTraceContext context = AgentTraceContext.capture(config, Map.of(GraphLifecycleListener.EXECUTION_ID_KEY, 42));

		assertThat(context.tenant()).isNull();
		assertThat(context.runId()).isNull();
		assertThat(context.threadId()).isEqualTo("t");
		assertThat(context.attemptId()).isNull();
	}

}
