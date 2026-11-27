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
package io.github.agentic.ai.agent.orchestration.durable;

import io.github.agentic.ai.agent.durable.DurableSession;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.workflow.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.workflow.graph.checkpoint.GraphCheckpointBoundary;
import io.github.agentic.ai.workflow.graph.serializer.StateSerializer;

import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/** Graph-owned encoding of engine state; model journals remain engine-neutral. */
public final class DurableCheckpointAdapter implements GraphCheckpointBoundary {

	private final DurableSession session;

	private final StateSerializer serializer;

	public DurableCheckpointAdapter(DurableSession session, StateSerializer serializer) {
		this.session = Objects.requireNonNull(session, "session cannot be null");
		this.serializer = Objects.requireNonNull(serializer, "serializer cannot be null");
	}

	public static Optional<DurableSession> sessionFrom(RunnableConfig config) {
		return config.checkpointBoundary()
			.filter(DurableCheckpointAdapter.class::isInstance)
			.map(DurableCheckpointAdapter.class::cast)
			.map(boundary -> boundary.session);
	}

	@Override
	public Checkpoint loadOrCreate(Checkpoint initial) throws Exception {
		session.start("graph-react", encode(initial));
		GraphState saved = DurableSession.decode(session.checkpoint().engineState(), GraphState.class);
		return Checkpoint.builder()
			.id(saved.id())
			.nodeId(saved.nodeId())
			.nextNodeId(saved.nextNodeId())
			.state(serializer.dataFromBytes(Base64.getDecoder().decode(saved.state())))
			.build();
	}

	@Override
	public void commit(Checkpoint checkpoint) throws Exception {
		session.commitCheckpoint(encode(checkpoint));
	}

	@Override
	public void complete(Checkpoint checkpoint) throws Exception {
		session.finish(encode(checkpoint));
	}

	@Override
	public ExecutionGuard guard() {
		return session.guard();
	}

	private RunPayload encode(Checkpoint checkpoint) throws Exception {
		String state = Base64.getEncoder().encodeToString(serializer.dataToBytes(checkpoint.getState()));
		return DurableSession.encode("argi.graph-react-state",
				new GraphState(checkpoint.getId(), checkpoint.getNodeId(), checkpoint.getNextNodeId(), state));
	}

	private record GraphState(String id, String nodeId, String nextNodeId, String state) {
	}

}
