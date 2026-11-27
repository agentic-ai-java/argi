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
package io.github.agentic.ai.runtime.execution.run;

import io.github.agentic.ai.runtime.execution.ExecutionControl;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunCommitCoordinatorTest {

	private final MemoryRunStore store = new MemoryRunStore();

	private final TrustedExecutionContext trusted = TrustedExecutionContext.builder()
		.tenant(new TenantReference("test", "tenant"))
		.principal(new PrincipalReference("test", "user"))
		.runId("run")
		.correlationId("trace")
		.build();

	private final RunSpec spec = RunSpec.from(trusted, "app", "session", "submission", "graph", "1",
			new RunPayload(1, "input", "{}"));

	private final RunPayload checkpoint = new RunPayload(1, "checkpoint", "{\"step\":1}");

	@Test
	void revisionConflictStopsAdvancementUntilExplicitReload() {
		RunSnapshot initial = store.submit(spec, trusted);
		RunLease lease = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), Duration.ofMinutes(1));
		try (ExecutionControl control = new ExecutionControl(trusted, Clock.systemUTC())) {
			var coordinator = new RunCommitCoordinator(store, initial, () -> lease, control);
			store.commit(spec.namespace(), spec.runId(), lease,
					new RunCommit(0, RunState.RUNNING, checkpoint, List.of(), List.of(), List.of()));

			assertThatThrownBy(() -> coordinator.commit(RunState.RUNNING, checkpoint, List.of()))
				.isInstanceOf(RunStoreConflictException.class);
			assertThatThrownBy(coordinator::snapshot).hasMessageContaining("Reload");
			assertThatThrownBy(() -> coordinator.commit(RunState.RUNNING, checkpoint, List.of()))
				.hasMessageContaining("Reload");
			assertThat(store.find(spec.namespace(), spec.runId()).orElseThrow().revision()).isEqualTo(1);

			assertThat(coordinator.reload().revision()).isEqualTo(1);
			assertThat(coordinator.commit(RunState.RUNNING, checkpoint, List.of()).revision()).isEqualTo(2);
		}
	}

	@Test
	void borrowsCurrentLeaseAndStopsCommitsAfterOwnershipLoss() {
		RunSnapshot initial = store.submit(spec, trusted);
		var lease = new AtomicReference<>(
				store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), Duration.ofMinutes(1)));
		try (ExecutionControl control = new ExecutionControl(trusted, Clock.systemUTC())) {
			var coordinator = new RunCommitCoordinator(store, initial, lease::get, control);
			lease.set(store.renew(lease.get(), Duration.ofMinutes(2)));
			RunEntry event = new RunEntry(1, "entry", RunEntry.Kind.MODEL_CALL, new RunPayload(1, "model", "{}"));
			RunSnapshot committed = coordinator.commit(RunState.RUNNING, checkpoint, List.of(event));
			assertThat(committed.checkpoint()).isEqualTo(checkpoint);
			assertThat(committed.journal()).containsEntry(event.id(), event);
			assertThat(coordinator.snapshot()).isSameAs(committed);

			control.loseOwnership();
			assertThatThrownBy(() -> coordinator.commit(RunState.RUNNING, checkpoint, List.of()))
				.isInstanceOf(RuntimeException.class);
			assertThat(store.find(spec.namespace(), spec.runId()).orElseThrow().revision()).isEqualTo(1);
			// Closing the invocation has no authority to release the caller's lease.
		}
		assertThat(store.renew(lease.get(), Duration.ofMinutes(2)).ownerId()).isEqualTo(lease.get().ownerId());
	}

}
