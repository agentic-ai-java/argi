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

import io.github.agentic.ai.graph.execution.TrustedExecutionContextReference;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Shared admission checks; no implicit schema upgrades or identity resolution. */
final class RunValidation {

	private RunValidation() {
	}

	static void text(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	static void version(int version) {
		if (version != 1) {
			throw new IllegalArgumentException("Unsupported schema version: " + version);
		}
	}

	static void context(TrustedExecutionContextReference context) {
		requireNonNull(context, "context cannot be null");
		version(context.schemaVersion());
	}

	static long ttlMillis(Duration ttl) {
		requireNonNull(ttl, "ttl cannot be null");
		if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(1)) > 0
				|| ttl.getNano() % 1_000_000 != 0) {
			throw new IllegalArgumentException("ttl must be positive whole milliseconds and at most one day");
		}
		return ttl.toMillis();
	}

}
