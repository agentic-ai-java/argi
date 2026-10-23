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

import java.time.Duration;

import static java.util.Objects.requireNonNull;

public record LeaseOptions(Duration ttl, Duration heartbeatInterval) {

	private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

	private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

	private static final Duration MAX_TTL = Duration.ofDays(1);

	public LeaseOptions {
		requireNonNull(ttl, "ttl cannot be null");
		requireNonNull(heartbeatInterval, "heartbeatInterval cannot be null");
		requirePositive(ttl, "ttl");
		requirePositive(heartbeatInterval, "heartbeatInterval");
		if (ttl.compareTo(MAX_TTL) > 0) {
			throw new IllegalArgumentException("ttl cannot exceed one day");
		}
		if (heartbeatInterval.compareTo(ttl) >= 0) {
			throw new IllegalArgumentException("heartbeatInterval must be below ttl");
		}
		requireWholeMilliseconds(ttl, "ttl");
		requireWholeMilliseconds(heartbeatInterval, "heartbeatInterval");
	}

	public static LeaseOptions defaults() {
		return new LeaseOptions(DEFAULT_TTL, DEFAULT_HEARTBEAT_INTERVAL);
	}

	private static void requirePositive(Duration duration, String name) {
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static void requireWholeMilliseconds(Duration duration, String name) {
		if (duration.toNanos() % 1_000_000L != 0) {
			throw new IllegalArgumentException(name + " must be a whole number of milliseconds");
		}
	}

}
