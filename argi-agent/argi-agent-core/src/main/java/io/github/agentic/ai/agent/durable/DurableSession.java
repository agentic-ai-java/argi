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
package io.github.agentic.ai.agent.durable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.runtime.execution.definition.RunDefinitionAdmission;
import io.github.agentic.ai.runtime.execution.definition.RuntimeComponentDeclaration;
import io.github.agentic.ai.runtime.execution.run.RunCommitCoordinator;
import io.github.agentic.ai.runtime.execution.run.RunEntry;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunState;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Invocation-local model-turn coordination shared by graph and independent agents. The
 * caller resolves the RunSpec's fixed executable definition before opening a session, and
 * owns the coordinator's lease/guard. Recreate the session after a failed commit and a
 * storage reload. Concurrent execution of one session is unsupported.
 */
public final class DurableSession {

	public static final String TASK_IDENTITY_ATTRIBUTE = "argi.durable.taskIdentity";

	private static final String CHECKPOINT_TYPE = "argi.agent-execution";

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private final RunCommitCoordinator coordinator;

	private final ModelTurnBindings bindings;

	private final ModelTurnAttemptPolicy policy;

	private final ModelTurnRecorder recorder = new ModelTurnRecorder();

	private Checkpoint checkpoint;

	private List<RunEntry> pending = List.of();

	private ChatResponse pendingResponse;

	private boolean invoked;

	private DurableSession(RunCommitCoordinator coordinator, ModelTurnBindings bindings,
						   ModelTurnAttemptPolicy policy) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
		this.bindings = Objects.requireNonNull(bindings, "bindings cannot be null");
		this.policy = Objects.requireNonNull(policy, "policy cannot be null");
		RunPayload stored = coordinator.snapshot().checkpoint();
		if (stored != null) {
			if (stored.schemaVersion() != 1 || !CHECKPOINT_TYPE.equals(stored.type())) {
				throw new ModelTurnJournalException("Unsupported agent execution checkpoint");
			}
			checkpoint = decode(stored, Checkpoint.class);
			if (!bindings.identity().equals(checkpoint.bindings()) || !policy.equals(checkpoint.policy())) {
				throw new ModelTurnJournalException("Model/tool/pipeline binding or attempt policy changed");
			}
			bindings.resolveTools(checkpoint.tools());
		}
	}

	/**
	 * Production entry: persistent storage and recoverable runtime components are
	 * required.
	 */
	public static DurableSession durable(RunCommitCoordinator coordinator, ModelTurnBindings bindings,
										 ModelTurnAttemptPolicy policy, Collection<RuntimeComponentDeclaration> components) {
		RunDefinitionAdmission.requireDurableMode(coordinator.capabilities(), components);
		return new DurableSession(coordinator, bindings, policy);
	}

	/**
	 * Explicit reference execution, including MemoryRunStore tests. This provides no
	 * cross-process durability and never changes a backend's advertised capabilities.
	 */
	public static DurableSession reference(RunCommitCoordinator coordinator, ModelTurnBindings bindings,
										   ModelTurnAttemptPolicy policy) {
		return new DurableSession(coordinator, bindings, policy);
	}

	public ExecutionGuard guard() {
		return coordinator.guard();
	}

	public String runId() {
		return coordinator.snapshot().spec().runId();
	}

	/**
	 * Initializes once. Recovery uses the saved state, never appends the original input
	 * again.
	 */
	public void start(String engine, RunPayload initialState) {
		guard().assertActive();
		if (checkpoint != null) {
			if (!engine.equals(checkpoint.engine())) {
				throw new ModelTurnJournalException("Checkpoint belongs to another execution engine");
			}
			return;
		}
		save(new Checkpoint(engine, 0, Phase.MODEL_READY, "model", initialState, new Usage(0L, 0L, 0L), null, List.of(),
				GenerationOptions.defaults(), bindings.identity(), policy), List.of(), RunState.RUNNING);
	}

	public Checkpoint checkpoint() {
		return Objects.requireNonNull(checkpoint, "Session has not started");
	}

	public ModelTurnRecorder.Turn turn() {
		return new ModelTurnRecorder.Turn(runId(),
				checkpoint.engine() + "/" + checkpoint.modelNode() + "/" + checkpoint.round());
	}

	/**
	 * Called after all request rewrites and effective tool selection, before the
	 * provider.
	 */
	public ToolRegistry reserve(String modelNode, ChatClient client, ChatRequest request) {
		guard().assertActive();
		if (client != bindings.client()) {
			throw new ModelTurnJournalException("Model call must use the registered versioned client");
		}
		if (checkpoint.phase() != Phase.MODEL_READY || invoked) {
			throw new ModelTurnJournalException("Only one model invocation per deterministic pipeline is supported");
		}
		ToolRegistry selected = bindings.resolveTools(request.tools());
		Checkpoint next = new Checkpoint(checkpoint.engine(), checkpoint.round(), Phase.MODEL_READY, modelNode,
				checkpoint.engineState(), checkpoint.usage(), ModelTurnCodec.requestHash(request), request.tools(),
				request.options(), bindings.identity(), policy);
		var turn = new ModelTurnRecorder.Turn(runId(), next.engine() + "/" + modelNode + "/" + next.round());
		List<RunEntry> reservation = recorder.prepareAttempt(turn, request, coordinator.snapshot(), policy);
		save(next, reservation, RunState.RUNNING);
		invoked = true;
		return selected;
	}

	/**
	 * Stage the final logical response; the engine supplies its next checkpoint before
	 * commit.
	 */
	public void prepareResponse(ChatResponse response) {
		if (!invoked || pendingResponse != null) {
			throw new ModelTurnJournalException("No reserved model call, or response already prepared");
		}
		pending = recorder.prepareResponse(turn(), response, checkpoint.tools());
		pendingResponse = response;
	}

	/**
	 * Atomic response/plan/checkpoint commit, or an ordinary engine checkpoint
	 * transition.
	 */
	public void commitCheckpoint(RunPayload engineState) {
		Checkpoint next;
		if (pendingResponse != null) {
			next = copy(Phase.TOOLS_READY, checkpoint.round(), engineState,
					add(checkpoint.usage(), pendingResponse.usage()), checkpoint.requestHash(), checkpoint.tools());
		}
		else if (checkpoint.phase() == Phase.TOOLS_ACTIVE) {
			next = copy(Phase.MODEL_READY, checkpoint.round() + 1, engineState, checkpoint.usage(), null, List.of());
		}
		else {
			next = copy(checkpoint.phase(), checkpoint.round(), engineState, checkpoint.usage(),
					checkpoint.requestHash(), checkpoint.tools());
		}
		save(next, pending, RunState.RUNNING);
		pending = List.of();
		pendingResponse = null;
		invoked = false;
	}

	public RecordedModelTurn recordedTurn() {
		guard().assertActive();
		if (checkpoint.requestHash() == null) {
			throw new ModelTurnJournalException("No committed model turn at this boundary");
		}
		return recorder
			.replayCommittedTurn(turn(), checkpoint.requestHash(), checkpoint.tools(), coordinator.snapshot())
			.orElseThrow(() -> new ModelTurnJournalException("Missing committed response"));
	}

	public ToolRegistry selectedTools() {
		return bindings.resolveTools(checkpoint.tools());
	}

	/**
	 * Provider IDs retain message correlation; persisted canonical arguments control
	 * execution.
	 */
	public List<ToolCall> plannedCalls() {
		RecordedModelTurn turn = recordedTurn();
		return turn.plans().stream().map(plan -> {
			ToolCall protocol = turn.response().toolCalls().get(plan.identity().ordinal());
			return new ToolCall(protocol.id(), plan.toolName(), plan.arguments());
		}).toList();
	}

	public ToolTaskIdentity taskIdentity(int ordinal) {
		return new ToolTaskIdentity(runId(), turn().turnId(), ordinal);
	}

	/**
	 * Persist the batch-start boundary before the first dispatch, without inventing tool
	 * receipts.
	 */
	public void beginTools() {
		RecordedModelTurn turn = recordedTurn();
		if (checkpoint.phase() == Phase.TOOLS_ACTIVE) {
			if (turn.plans().stream().anyMatch(plan -> !bindings.replayableTools().contains(plan.toolName()))) {
				throw new ModelTurnJournalException("Tool result is uncertain; receipt reconciliation is required");
			}
			return;
		}
		if (checkpoint.phase() != Phase.TOOLS_READY) {
			throw new ModelTurnJournalException("Tool batch has no committed plan");
		}
		save(copy(Phase.TOOLS_ACTIVE, checkpoint.round(), checkpoint.engineState(), checkpoint.usage(),
				checkpoint.requestHash(), checkpoint.tools()), List.of(), RunState.RUNNING);
	}

	public void finish(RunPayload engineState) {
		if (checkpoint.phase() != Phase.FINISHED) {
			save(copy(Phase.FINISHED, checkpoint.round(), engineState, checkpoint.usage(), checkpoint.requestHash(),
					checkpoint.tools()), List.of(), RunState.SUCCEEDED);
		}
	}

	private Checkpoint copy(Phase phase, long round, RunPayload engineState, Usage usage, String hash,
			List<ToolDefinition> tools) {
		return new Checkpoint(checkpoint.engine(), round, phase, checkpoint.modelNode(), engineState, usage, hash,
				tools, checkpoint.options(), bindings.identity(), policy);
	}

	private void save(Checkpoint next, List<RunEntry> entries, RunState state) {
		coordinator.commit(state, encode(CHECKPOINT_TYPE, next), entries);
		checkpoint = next;
	}

	public static RunPayload encode(String type, Object value) {
		try {
			return new RunPayload(1, type, MAPPER.writeValueAsString(value));
		}
		catch (JsonProcessingException failure) {
			throw new ModelTurnJournalException("Cannot encode execution checkpoint", failure);
		}
	}

	public static <T> T decode(RunPayload payload, Class<T> type) {
		try {
			return MAPPER.readValue(payload.json(), type);
		}
		catch (JsonProcessingException failure) {
			throw new ModelTurnJournalException("Cannot decode execution checkpoint", failure);
		}
	}

	private static Usage add(Usage left, Usage right) {
		return new Usage(add(left.inputTokens(), right.inputTokens()), add(left.outputTokens(), right.outputTokens()),
				add(left.totalTokens(), right.totalTokens()));
	}

	private static Long add(Long left, Long right) {
		return left == null || right == null ? null : left + right;
	}

	public enum Phase {

		MODEL_READY, TOOLS_READY, TOOLS_ACTIVE, FINISHED

	}

	/**
	 * Versioned data only. Engine state is encoded by the corresponding engine adapter.
	 */
	public record Checkpoint(String engine, long round, Phase phase, String modelNode, RunPayload engineState,
			Usage usage, String requestHash, List<ToolDefinition> tools, GenerationOptions options,
			ModelTurnBindings.Identity bindings, ModelTurnAttemptPolicy policy) {
		public Checkpoint {
			tools = List.copyOf(tools);
		}
	}

}
