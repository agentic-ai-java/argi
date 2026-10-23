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
package io.github.agentic.ai.graph.checkpoint;

/**
 * Raised when a conditional checkpoint mutation observes a namespace revision different
 * from the caller's expected revision.
 * <p>
 * The exception intentionally carries only namespace and revision metadata, never
 * checkpoint state payloads. {@code actualRevision} is the latest revision observed by
 * the saver for diagnostics, or {@code -1} when that diagnostic read failed and the
 * failure is attached as a suppressed exception. It is not authority to retry a failed
 * mutation.
 */
public class CheckpointConflictException extends Exception {

	private final String namespace;

	private final long expectedRevision;

	private final long actualRevision;

	public CheckpointConflictException(String namespace, long expectedRevision, long actualRevision) {
		super("Checkpoint namespace '%s' expected revision %d but was %d"
			.formatted(namespace, expectedRevision, actualRevision));
		this.namespace = namespace;
		this.expectedRevision = expectedRevision;
		this.actualRevision = actualRevision;
	}

	public String getNamespace() {
		return namespace;
	}

	public long getExpectedRevision() {
		return expectedRevision;
	}

	public long getActualRevision() {
		return actualRevision;
	}

}
