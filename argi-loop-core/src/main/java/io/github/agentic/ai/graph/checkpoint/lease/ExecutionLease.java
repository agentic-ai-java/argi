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
package io.github.agentic.ai.graph.checkpoint.lease;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record ExecutionLease(String namespace, UUID ownerId, long fencingToken, long expiresAtMillis) {

	public ExecutionLease {
		requireNonNull(namespace, "namespace cannot be null");
		requireNonNull(ownerId, "ownerId cannot be null");
		if (namespace.isEmpty()) {
			throw new IllegalArgumentException("namespace cannot be empty");
		}
		if (fencingToken <= 0) {
			throw new IllegalArgumentException("fencingToken must be positive");
		}
		if (expiresAtMillis <= 0) {
			throw new IllegalArgumentException("expiresAtMillis must be positive");
		}
	}

}
