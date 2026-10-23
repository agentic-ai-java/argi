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

/**
 * Explicit storage guarantees. Advertising these guarantees is an implementer obligation,
 * verified by contract and backend tests, not inferred from implementing RunStore.
 */
public record RunStoreCapabilities(boolean persistent, boolean atomicRunCheckpointOutbox, boolean namespaceFencing,
		boolean sessionReservation, boolean submissionDeduplication, boolean revisionCompareAndSet) {

	public boolean supportsDurableMode() {
		return persistent && atomicRunCheckpointOutbox && namespaceFencing && sessionReservation
				&& submissionDeduplication && revisionCompareAndSet;
	}

	/** Applications must fail closed at opt-in, before admitting durable work. */
	public void requireDurableMode() {
		if (!supportsDurableMode()) {
			throw new IllegalStateException("Store lacks required durable run guarantees: " + this);
		}
	}
}
