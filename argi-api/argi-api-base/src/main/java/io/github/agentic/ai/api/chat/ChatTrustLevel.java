/*
 * Copyright 2025-2026 the original author or authors.
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

/**
 * Optional trust classification for message content.
 *
 * <p>
 * Ordering is from highest authority to lowest: {@link #TRUSTED}, {@link #USER},
 * {@link #DERIVED}, {@link #UNTRUSTED}. A lower level must never be promoted by
 * summarization or context merge alone.
 */
public enum ChatTrustLevel {

	/** Application-owned authoritative instructions or configuration. */
	TRUSTED,

	/** End-user content that may guide behavior but is not application authority. */
	USER,

	/** Content produced by compaction or summarization of other messages. */
	DERIVED,

	/** External or untrusted observations such as tool and retrieval payloads. */
	UNTRUSTED;

	/** Returns {@code true} when this level is strictly lower authority than {@code other}. */
	public boolean isLowerThan(ChatTrustLevel other) {
		return other != null && ordinal() > other.ordinal();
	}

	/** Returns the lower-authority level of the two. */
	public ChatTrustLevel min(ChatTrustLevel other) {
		if (other == null) {
			return this;
		}
		return ordinal() >= other.ordinal() ? this : other;
	}

}
