/*
 * Copyright 2024-2026 the original author or authors.
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

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Policy controlling how checkpoint savers treat a {@link RunnableConfig} without a
 * {@code threadId}.
 * <p>
 * By default a missing {@code threadId} falls back to the shared
 * {@link BaseCheckpointSaver#THREAD_ID_DEFAULT} checkpoint namespace: every caller
 * without a threadId on the same saver instance (scoped further by the {@code app_name}
 * and {@code user_id} metadata entries when present) shares one checkpoint history.
 * This policy makes that fallback observable and optionally strict:
 * <ul>
 * <li><strong>Warning mode (default):</strong> the fallback is logged once so that
 * accidental cross-caller sharing becomes visible in application logs.</li>
 * <li><strong>Strict mode:</strong> checkpoint operations fail fast with
 * {@link IllegalArgumentException} until the caller provides an explicit threadId.
 * Recommended for persistence-backed or multi-user deployments.</li>
 * </ul>
 * Strict mode is enabled through the {@link #REQUIRE_EXPLICIT_THREAD_ID_PROPERTY} system
 * property or programmatically via {@link #setRequireExplicitThreadId(boolean)}.
 */
public final class CheckpointThreadIdPolicy {

	/**
	 * System property that enables the strict thread-id policy at startup. When set to
	 * {@code true}, {@link BaseCheckpointSaver#checkpointThreadId(RunnableConfig)}
	 * rejects configurations without an explicit threadId instead of falling back to the
	 * shared default namespace.
	 */
	public static final String REQUIRE_EXPLICIT_THREAD_ID_PROPERTY = "argi.checkpoint.requireExplicitThreadId";

	private static final Logger log = LoggerFactory.getLogger(CheckpointThreadIdPolicy.class);

	private static final AtomicBoolean MISSING_THREAD_ID_WARNING_LOGGED = new AtomicBoolean(false);

	private static volatile boolean requireExplicitThreadId = Boolean.getBoolean(REQUIRE_EXPLICIT_THREAD_ID_PROPERTY);

	private CheckpointThreadIdPolicy() {
	}

	/**
	 * Returns whether checkpoint operations must fail when the configuration has no
	 * explicit threadId.
	 * @return {@code true} when the strict thread-id policy is enabled
	 */
	public static boolean isRequireExplicitThreadId() {
		return requireExplicitThreadId;
	}

	/**
	 * Enables or disables the strict thread-id policy at runtime. Disabling restores the
	 * startup default derived from {@link #REQUIRE_EXPLICIT_THREAD_ID_PROPERTY}.
	 * @param enabled {@code true} to reject missing threadIds, {@code false} to warn and
	 * fall back to the shared default namespace
	 */
	public static void setRequireExplicitThreadId(boolean enabled) {
		requireExplicitThreadId = enabled;
	}

	/**
	 * Logs the missing-threadId fallback warning once per JVM so that shared
	 * {@code $default} namespace usage becomes visible without flooding the logs.
	 */
	static void warnOnMissingThreadId() {
		if (MISSING_THREAD_ID_WARNING_LOGGED.compareAndSet(false, true)) {
			log.warn(
					"RunnableConfig has no threadId; falling back to the shared \"{}\" checkpoint namespace "
							+ "(scoped by app_name/user_id metadata when present). All callers without a threadId "
							+ "on the same saver instance share this checkpoint history and can read each other's "
							+ "checkpoints. Set an explicit threadId to isolate conversations, or enable the strict "
							+ "policy with system property {}=true or CheckpointThreadIdPolicy.setRequireExplicitThreadId(true). "
							+ "This warning is logged only once.",
					BaseCheckpointSaver.THREAD_ID_DEFAULT, REQUIRE_EXPLICIT_THREAD_ID_PROPERTY);
		}
	}

}
