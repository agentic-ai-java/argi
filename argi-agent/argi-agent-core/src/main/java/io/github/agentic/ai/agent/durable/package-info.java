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
 * stable identity for every planned tool call, before the engine dispatches tools. A
 * recovery then replays the recorded plan instead of asking the model again.
 *
 * <p>
 * The contract deliberately knows no graph, session, scheduler or streaming library: it
 * depends on the neutral chat contracts and on {@code io.github.agentic.ai.runtime.execution.run}.
 * Graph-backed and graph-independent engines share it, and each supplies stable stream
 * and turn identities. The engine restores the same identity for a recovered logical call
 * and gives different calls distinct turn ids within a stream.
 *
 * <p>
 * The engine is responsible for one ordering rule: {@link io.github.agentic.ai.agent.durable.ModelTurnRecorder#commit}
 * must succeed before the first tool of that turn is dispatched. A failed commit means the
 * plan was never recorded, so no tool may run.
 */
package io.github.agentic.ai.agent.durable;
