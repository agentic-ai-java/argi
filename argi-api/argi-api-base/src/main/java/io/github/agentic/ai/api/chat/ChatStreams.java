/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.api.chat;

import java.util.Objects;

/** Shared append-only text invariant for adapters and runtime consumers. */
public final class ChatStreams {

	private ChatStreams() {
	}

	/**
	 * Returns the aggregate suffix not yet emitted. Already delivered text cannot be
	 * retracted, so provider normalization that rewrites it must fail explicitly.
	 */
	public static String unseenSuffix(String aggregate, CharSequence emitted) {
		Objects.requireNonNull(aggregate, "aggregate must not be null");
		Objects.requireNonNull(emitted, "emitted must not be null");
		if (!aggregate.startsWith(emitted.toString())) {
			throw new IllegalStateException("Final response text does not match streamed deltas");
		}
		return aggregate.substring(emitted.length());
	}

}
