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
package io.github.agentic.ai.graph.execution.durable;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.lease.ExecutionLease;
import io.github.agentic.ai.graph.checkpoint.lease.LeaseLostException;
import io.github.agentic.ai.graph.execution.run.MemoryRunStore;
import io.github.agentic.ai.graph.execution.run.RunPayload;
import io.github.agentic.ai.graph.execution.run.RunState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.OPTIONS;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.RECORD;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.context;
import static io.github.agentic.ai.graph.execution.durable.DurableTestSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableBoundaryTest {
	private final JsonDurableStateCodec codec = new JsonDurableStateCodec();
	private final DurableTestSupport.MutableClock clock = new DurableTestSupport.MutableClock();
	private final MemoryRunStore store = new MemoryRunStore(clock);
	private final RunnableConfig config = RunnableConfig.builder().threadId("run").build();

	private Checkpoint checkpoint(Map<String, Object> state) {
		return Checkpoint.builder().id("checkpoint").nodeId(StateGraph.START).nextNodeId("work").state(state).build();
	}

	private RunCheckpointSaver saver() {
		var spec = spec(context());
		var snapshot = store.submit(spec, context());
		var lease = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl());
		return new RunCheckpointSaver(store, snapshot, lease, codec, OPTIONS, ignored -> { }, () -> false);
	}

	@Test
	void dataEnvelopePinsDefinitionsAndRejectsUnknownOrMalformedData() {
		DurableRunInput input = new DurableRunInput(RECORD, spec(context()).input());
		assertThat(DurableRunInput.decode(input.encode())).isEqualTo(input);
		assertThatThrownBy(() -> DurableRunInput.decode(new RunPayload(2, "argi.durable.input", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DurableRunInput.decode(new RunPayload(1, "wrong", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DurableRunInput.decode(new RunPayload(1, "argi.durable.input", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DurableRunInput(null, input.input())).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new DurableGraphDefinition((definitions, saver) -> null, null, List.of()))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void jsonCodecRoundTripsNestedValuesWithoutObjectSerialization() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("null", null);
		values.put("values", Arrays.asList("text", true, 1, 2L, (byte) 3, (short) 4,
				new java.math.BigInteger("123"), new java.math.BigDecimal("1.25"), 1.5D, 2.5F,
				Map.of("nested", "value")));
		Checkpoint original = checkpoint(values);
		Checkpoint restored = codec.decodeCheckpoint(codec.encodeCheckpoint(original));
		assertThat(restored.getId()).isEqualTo(original.getId());
		assertThat(restored.getNodeId()).isEqualTo(original.getNodeId());
		assertThat(restored.getNextNodeId()).isEqualTo(original.getNextNodeId());
		assertThat(restored.getState()).containsKey("null");
		assertThat(codec.decodeInput(spec(context()).input())).containsEntry("value", 1);
		for (Object unsupported : List.of(new Object(), Double.NaN, Float.POSITIVE_INFINITY, Map.of(1, "bad"))) {
			assertThatThrownBy(() -> codec.encodeCheckpoint(checkpoint(Map.of("bad", unsupported))))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> codec.decodeInput(new RunPayload(2, "input", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decodeCheckpoint(new RunPayload(2, "argi.durable.checkpoint", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decodeCheckpoint(new RunPayload(1, "wrong", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decodeCheckpoint(new RunPayload(1, "argi.durable.checkpoint", "{}")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void checkpointAdapterEnforcesCommittedBoundaryAndOwnerOnlyMutations() {
		RunCheckpointSaver saver = saver();
		assertThat(saver.list(config)).isEmpty();
		assertThat(saver.getVersioned(config).revision()).isZero();
		assertThatThrownBy(() -> saver.get(config.withCheckPointId("missing")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> saver.get(RunnableConfig.builder().threadId("other").build()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> saver.acquireLease(config, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> saver.releaseLease(config, saver.executionLease())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> saver.releaseIfLeasedVersion(config, 0, saver.executionLease()))
			.isInstanceOf(IllegalStateException.class);
		saver.start(checkpoint(Map.of("value", 1)));
		assertThat(saver.list(config)).hasSize(1);
		assertThat(saver.get(config.withCheckPointId("checkpoint"))).isPresent();
		saver.putIfLeasedVersion(config, checkpoint(Map.of("value", 2)), 1, saver.executionLease());
		assertThat(saver.getVersioned(config).revision()).isEqualTo(2);
		assertThat(saver.get(config).orElseThrow().getState()).containsEntry("value", 2);
		assertThat(saver.finish(RunState.SUCCEEDED).state()).isEqualTo(RunState.SUCCEEDED);
		assertThat(saver.finish(RunState.SUCCEEDED).revision()).isEqualTo(3);
		assertThatThrownBy(() -> saver.putIfLeasedVersion(config, checkpoint(Map.of()), 3, saver.executionLease()))
			.isInstanceOf(LeaseLostException.class);
		saver.release();
	}

	@Test
	void staleRevisionOrOwnerCannotMutateOrContinueAfterLoss() {
		RunCheckpointSaver saver = saver();
		saver.start(checkpoint(Map.of()));
		assertThatThrownBy(() -> saver.putIfLeasedVersion(config, checkpoint(Map.of("late", true)),
				0, saver.executionLease())).isInstanceOf(LeaseLostException.class);
		assertThat(saver.lost()).isTrue();
		assertThatThrownBy(() -> saver.finish(RunState.FAILED)).isInstanceOf(LeaseLostException.class);
		assertThat(saver.snapshot().revision()).isEqualTo(1);
		saver.release();
	}

	@Test
	void expiredLeaseCannotRenewAndReplacementSurvivesOldRelease() {
		RunCheckpointSaver saver = saver();
		saver.start(checkpoint(Map.of()));
		clock.advance(Duration.ofSeconds(3));
		var spec = spec(context());
		var replacement = store.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), OPTIONS.ttl());
		assertThatThrownBy(() -> saver.renewLease(config, saver.executionLease())).isInstanceOf(LeaseLostException.class);
		saver.release();
		assertThat(store.renew(replacement, OPTIONS.ttl()).ownerId()).isEqualTo(replacement.ownerId());
	}

	@Test
	void mismatchedLeaseCredentialsAreRejectedLocally() {
		RunCheckpointSaver saver = saver();
		ExecutionLease lease = saver.executionLease();
		ExecutionLease other = new ExecutionLease(lease.namespace(), UUID.randomUUID(), lease.fencingToken(),
				lease.expiresAtMillis());
		assertThatThrownBy(() -> saver.renewLease(config, other)).isInstanceOf(LeaseLostException.class);
		saver.release();
	}
}
