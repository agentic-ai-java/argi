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

import io.github.agentic.ai.graph.RunnableConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public interface BaseCheckpointSaver {
	String THREAD_ID_DEFAULT = "$default";
	String CHECKPOINTS_NUM_RETAINED = "checkpoints.numRetained";

	default Optional<Checkpoint> getLast(LinkedList<Checkpoint> checkpoints, RunnableConfig config) {
		return (checkpoints.isEmpty()) ? Optional.empty() : ofNullable(checkpoints.peek());
	}

	default Optional<Integer> checkpointsNumRetained(RunnableConfig config) {
		return config.metadata(CHECKPOINTS_NUM_RETAINED).map(value -> {
			if (value instanceof Number number) {
				return number.intValue();
			}
			if (value instanceof String text) {
				return Integer.parseInt(text);
			}
			throw new IllegalArgumentException(
					"checkpoints.numRetained must be a number or numeric string, got: " + value.getClass().getName());
		}).filter(value -> value > 0);
	}

	default void retainLatestCheckpoints(LinkedList<Checkpoint> checkpoints, RunnableConfig config) {
		checkpointsNumRetained(config).ifPresent(numRetained -> {
			while (checkpoints.size() > numRetained) {
				checkpoints.removeLast();
			}
		});
	}

	/**
	 * Resolves the checkpoint namespace for the given configuration. The namespace is
	 * the explicit {@link RunnableConfig#threadId()} when present, otherwise the shared
	 * {@link #THREAD_ID_DEFAULT} bucket, scoped by the {@code app_name} and
	 * {@code user_id} metadata entries when those are present. Because
	 * {@code THREAD_ID_DEFAULT} is a shared bucket, callers that omit the threadId
	 * observe each other's checkpoints on the same saver instance; the fallback is
	 * logged as a warning once, and {@link CheckpointThreadIdPolicy} can be used to
	 * reject missing threadIds with {@link IllegalArgumentException} instead.
	 * @param config the configuration of the checkpoint call, never {@code null}
	 * @return the checkpoint namespace to use for the given configuration
	 */
	default String checkpointThreadId(RunnableConfig config) {
		Optional<String> explicitThreadId = config.threadId();
		if (explicitThreadId.isEmpty()) {
			if (CheckpointThreadIdPolicy.isRequireExplicitThreadId()) {
				throw new IllegalArgumentException(
						"RunnableConfig must provide an explicit threadId: the strict checkpoint thread-id policy is "
								+ "enabled via " + CheckpointThreadIdPolicy.REQUIRE_EXPLICIT_THREAD_ID_PROPERTY
								+ " (or CheckpointThreadIdPolicy.setRequireExplicitThreadId(true)), and a missing "
								+ "threadId would share the \"" + THREAD_ID_DEFAULT + "\" checkpoint namespace across callers");
			}
			CheckpointThreadIdPolicy.warnOnMissingThreadId();
		}
		String threadId = explicitThreadId.orElse(THREAD_ID_DEFAULT);
		String appName = metadataText(config, RunnableConfig.APP_NAME_METADATA_KEY).orElse(null);
		String userId = metadataText(config, RunnableConfig.USER_ID_METADATA_KEY).orElse(null);
		if (appName == null && userId == null) {
			return threadId;
		}
		return "ns-%s.%s.%s".formatted(namespaceSegment(appName), namespaceSegment(userId), namespaceSegment(threadId));
	}

	private String namespaceSegment(String value) {
		String normalized = value != null ? value : THREAD_ID_DEFAULT;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
	}

	private Optional<String> metadataText(RunnableConfig config, String key) {
		return config.metadata(key)
				.map(String::valueOf)
				.map(String::trim)
				.filter(value -> !value.isEmpty());
	}

	Collection<Checkpoint> list(RunnableConfig config);

	Optional<Checkpoint> get(RunnableConfig config);

	RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception;

	Tag release(RunnableConfig config) throws Exception;

	/**
	 * Explicitly moves checkpoints from an unscoped legacy thread key into a scoped
	 * app/user namespace. Runtime reads deliberately do not fall back to the legacy key,
	 * because doing so would reintroduce cross-user checkpoint access.
	 * @return the number of migrated checkpoints
	 */
	default int migrateLegacyThread(RunnableConfig legacyConfig, RunnableConfig namespacedConfig) throws Exception {
		String legacyThreadId = legacyConfig.threadId().orElse(THREAD_ID_DEFAULT);
		if (!checkpointThreadId(legacyConfig).equals(legacyThreadId)) {
			throw new IllegalArgumentException("Legacy config must not contain checkpoint namespace metadata");
		}
		if (checkpointThreadId(namespacedConfig).equals(legacyThreadId)) {
			throw new IllegalArgumentException("Target config must contain app or user namespace metadata");
		}

		List<Checkpoint> legacyCheckpoints = List.copyOf(list(legacyConfig));
		if (legacyCheckpoints.isEmpty()) {
			return 0;
		}
		if (!list(namespacedConfig).isEmpty()) {
			throw new IllegalStateException("Target checkpoint namespace is not empty");
		}

		for (int index = legacyCheckpoints.size() - 1; index >= 0; index--) {
			put(namespacedConfig, legacyCheckpoints.get(index));
		}
		release(legacyConfig);
		return legacyCheckpoints.size();
	}

	record Tag(String threadId, Collection<Checkpoint> checkpoints) {
		public Tag(String threadId, Collection<Checkpoint> checkpoints) {
			this.threadId = threadId;
			this.checkpoints = ofNullable(checkpoints).map(List::copyOf).orElseGet(List::of);
		}
	}

}
