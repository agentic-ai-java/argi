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

import io.github.agentic.ai.graph.execution.TrustedExecutionContext;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Backend-neutral, opt-in run storage. Every mutation is linearizable in its namespace.
 * Implementations must validate owner/fence, lease expiry, revision, transition and all
 * appended data inside the SAME atomic boundary as the write. Composing independent
 * checkpoint and event stores does not satisfy this contract. No default durability
 * stubs.
 *
 * Applications own authentication/authorization for reads and worker access. Submission
 * requires a live trusted context; persisted identity references alone cannot admit work.
 * Recovery re-resolves identity and registered code before executing any side effects.
 */
public interface RunStore {

	RunStoreCapabilities capabilities();

	/**
	 * Atomically deduplicate by namespace+submissionKey and reserve the session. Same key
	 * and canonical input/definition/principal returns the original run (including terminal
	 * runs); changed input/definition/principal conflicts. Original constraints are retained.
	 * A different run/key conflicts while any nonterminal run reserves the session. New
	 * admissions reject expired deadlines. No record is left on failed admission.
	 */
	RunSnapshot submit(RunSpec spec, TrustedExecutionContext context);

	Optional<RunSnapshot> find(RunNamespace namespace, String runId);

	Optional<String> activeRun(RunNamespace namespace);

	/** Acquire the physical namespace lease only for its logically reserved run. */
	RunLease acquire(RunNamespace namespace, String runId, UUID ownerId, Duration ttl);

	RunLease renew(RunLease lease, Duration ttl);

	/** Releases only a matching physical lease, never the session reservation. */
	boolean release(RunLease lease);

	/**
	 * Atomically commit run+checkpoint+tasks+journal+outbox. Terminal commits also
	 * release reservation and physical lease. Expired deadlines allow only
	 * TIMED_OUT/CANCELLED commits; task updates enforce narrowed deadlines the same way.
	 * Deadline/budget enforcement around model/tool calls is the runtime's duty; this
	 * storage contract does not imply usage accounting or exactly-once side effects.
	 */
	RunSnapshot commit(RunNamespace namespace, String runId, RunLease lease, RunCommit commit);

}
