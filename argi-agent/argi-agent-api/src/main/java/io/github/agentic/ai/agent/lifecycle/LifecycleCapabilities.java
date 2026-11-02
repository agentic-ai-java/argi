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
package io.github.agentic.ai.agent.lifecycle;

/**
 * Capability flags declared by a lifecycle extension.
 *
 * <p>
 * A capability answers a single question: does the extension support this execution
 * mode at its bound stage? The flags are descriptive, never a promise that every engine
 * provides every stage. An engine that cannot honour a flag must say so instead of
 * silently degrading the extension.
 *
 * @param synchronous whether the extension runs on the calling thread
 * @param asynchronous whether the extension may complete on another thread
 * @param cancellable whether the extension observes cancellation of the bound stage
 * @param streaming whether the extension participates in a streamed stage
 * @param resumable whether the extension survives a suspend and resume of the bound
 * stage
 */
public record LifecycleCapabilities(boolean synchronous, boolean asynchronous, boolean cancellable, boolean streaming,
		boolean resumable) {

	/**
	 * No capability at all.
	 * @return capabilities without any supported execution mode
	 */
	public static LifecycleCapabilities none() {
		return new LifecycleCapabilities(false, false, false, false, false);
	}

	/**
	 * The extension only runs synchronously on the calling thread.
	 * @return synchronous-only capabilities
	 */
	public static LifecycleCapabilities synchronousOnly() {
		return new LifecycleCapabilities(true, false, false, false, false);
	}

	/**
	 * The extension only completes asynchronously.
	 * @return asynchronous-only capabilities
	 */
	public static LifecycleCapabilities asynchronousOnly() {
		return new LifecycleCapabilities(false, true, false, false, false);
	}

	/**
	 * Whether the extension supports at least one execution mode.
	 * @return true when any capability is declared
	 */
	public boolean supportsAny() {
		return synchronous || asynchronous || cancellable || streaming || resumable;
	}
}
