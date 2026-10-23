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
package io.github.agentic.ai.graph.checkpoint.savers;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySaverNamespaceTest {

	@Test
	void checkpointHistoryIsIsolatedByAppAndUserMetadataWhenThreadIdIsShared() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		Checkpoint aliceCheckpoint = checkpoint("alice");
		Checkpoint bobCheckpoint = checkpoint("bob");

		saver.put(config("assistant", "alice", "shared-thread"), aliceCheckpoint);
		saver.put(config("assistant", "bob", "shared-thread"), bobCheckpoint);

		assertThat(saver.get(config("assistant", "alice", "shared-thread")))
				.hasValueSatisfying(checkpoint -> assertThat(checkpoint.getState()).containsEntry("owner", "alice"));
		assertThat(saver.get(config("assistant", "bob", "shared-thread")))
				.hasValueSatisfying(checkpoint -> assertThat(checkpoint.getState()).containsEntry("owner", "bob"));
		assertThat(saver.list(config("assistant", "alice", "shared-thread"))).hasSize(1);
		assertThat(saver.list(config("assistant", "bob", "shared-thread"))).hasSize(1);
	}

	@Test
	void checkpointNamespaceCannotCollideWhenMetadataContainsSeparators() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();

		saver.put(config("a:b", "c", "shared-thread"), checkpoint("first"));
		saver.put(config("a", "b:c", "shared-thread"), checkpoint("second"));

		assertThat(saver.get(config("a:b", "c", "shared-thread")))
				.hasValueSatisfying(checkpoint -> assertThat(checkpoint.getState()).containsEntry("owner", "first"));
		assertThat(saver.get(config("a", "b:c", "shared-thread")))
				.hasValueSatisfying(checkpoint -> assertThat(checkpoint.getState()).containsEntry("owner", "second"));
	}

	@Test
	void legacyCheckpointHistoryCanBeExplicitlyMigratedWithoutRuntimeFallback() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		RunnableConfig legacy = RunnableConfig.builder().threadId("legacy-thread").build();
		RunnableConfig namespaced = config("assistant", "alice", "legacy-thread");
		saver.put(legacy, checkpoint("oldest"));
		saver.put(legacy, checkpoint("newest"));

		saver.migrateLegacyThread(legacy, namespaced);

		assertThat(saver.list(legacy)).isEmpty();
		assertThat(saver.list(namespaced))
				.extracting(checkpoint -> checkpoint.getState().get("owner"))
				.containsExactly("newest", "oldest");
	}

	private static RunnableConfig config(String appName, String userId, String threadId) {
		return RunnableConfig.builder()
				.threadId(threadId)
				.addMetadata("app_name", appName)
				.addMetadata("user_id", userId)
				.build();
	}

	private static Checkpoint checkpoint(String owner) {
		return Checkpoint.builder()
				.id(owner + "-checkpoint")
				.state(Map.of("owner", owner))
				.nodeId("node")
				.nextNodeId("next")
				.build();
	}

}
