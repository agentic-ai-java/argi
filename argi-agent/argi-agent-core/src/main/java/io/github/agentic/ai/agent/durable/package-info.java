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

/**
 * Engine-neutral journaling of model turns.
 *
 * <p>
 * The non-deterministic result of a model call — the assistant message, its tool calls,
 * finish reason and usage — cannot be regenerated. These types record it, together with a
 * stable identity for every planned tool call. Preparation is stateless and has no storage
 * side effects. The runtime persists the data before dispatching tools; recovery then
 * validates and replays the recorded result instead of asking the model again.
 *
 * <p>
 * The contract deliberately knows no graph, session, scheduler or streaming library: it
 * depends on the neutral chat contracts and on {@code io.github.agentic.ai.runtime.execution.run}.
 * Graph-backed and graph-independent engines share it, and each supplies stable run
 * and turn identities. The engine restores the same identity for a recovered logical call
 * and gives different calls distinct turn ids within a run.
 *
 * <p>
 * The runtime explicitly enables journaling and owns the store, lease and latest committed
 * snapshot. It commits a prepared request fingerprint before calling the model, then
 * commits the full response and plan batch together with the corresponding checkpoint
 * through {@link io.github.agentic.ai.runtime.execution.run.RunStore#commit} before
 * dispatching the first tool. Planned TASK_CALL entries are journal data, not task lifecycle
 * updates in {@code RunCommit.tasks}.
 *
 * <p>
 * All writes share one runtime coordinator. Request fingerprints and diagnostic deltas
 * retain the checkpoint. The runtime settles in-flight delta commits before submitting the
 * terminal response, may drop unsubmitted deltas, and propagates storage failures by default.
 * An uncertain commit outcome requires resolving the stored state before continuing from a
 * cached snapshot; revision conflicts and ownership loss must not be ignored.
 *
 * <p>
 * Replay checks a single supplied snapshot for compatible request/response records and an
 * exact matching tool plan. It cannot prove that records were committed together, interpret
 * application checkpoints or detect stale reads. An empty replay must be followed by a
 * request commit using that snapshot's revision; a conflict requires reloading and replaying
 * again. Durable-mode admission, definition and identity recovery, execution positions and
 * tool receipts remain runtime responsibilities. An in-memory reference store only models
 * recovery within one process.
 */
package io.github.agentic.ai.agent.durable;
