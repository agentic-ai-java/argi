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
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.CheckpointThreadIdPolicy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for issue #154: a missing {@code threadId} must not silently share
 * the default checkpoint namespace across callers without the sharing semantics being
 * observable and optionally strict.
 */
class CheckpointThreadIdPolicyTest {

	@AfterEach
	void restoreStartupPolicy() {
		CheckpointThreadIdPolicy.setRequireExplicitThreadId(
				Boolean.getBoolean(CheckpointThreadIdPolicy.REQUIRE_EXPLICIT_THREAD_ID_PROPERTY));
	}

	@Test
	void twoConfigsWithoutThreadIdShareTheDefaultNamespaceOnOneSaver() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		RunnableConfig callerA = RunnableConfig.builder().build();
		RunnableConfig callerB = RunnableConfig.builder().build();

		saver.put(callerA, checkpoint("USER_A"));

		assertThat(saver.checkpointThreadId(callerA)).isEqualTo(BaseCheckpointSaver.THREAD_ID_DEFAULT);
		assertThat(saver.get(callerB)).isPresent();
		assertThat(saver.get(callerB).orElseThrow().getState())
				.containsEntry("owner", "USER_A")
				.containsEntry("secret", "user_a-private");
	}

	@Test
	void strictPolicyRejectsCheckpointCallsWithoutThreadId() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		CheckpointThreadIdPolicy.setRequireExplicitThreadId(true);
		RunnableConfig anonymous = RunnableConfig.builder().build();

		assertThatThrownBy(() -> saver.get(anonymous)).hasCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> saver.list(anonymous)).hasCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> saver.put(anonymous, checkpoint("USER_A")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("threadId");
		assertThatThrownBy(() -> saver.checkpointThreadId(anonymous)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void strictPolicyAcceptsExplicitThreadId() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		CheckpointThreadIdPolicy.setRequireExplicitThreadId(true);
		RunnableConfig config = RunnableConfig.builder().threadId("conversation-1").build();

		saver.put(config, checkpoint("USER_A"));

		assertThat(saver.get(config)).isPresent();
		assertThat(saver.get(config).orElseThrow().getState()).containsEntry("owner", "USER_A");
	}

	@Test
	void appAndUserMetadataWithoutThreadIdScopeTheDefaultBucketPerIdentity() throws Exception {
		MemorySaver saver = MemorySaver.builder().build();
		RunnableConfig alice = namespacedConfig("assistant", "alice");
		RunnableConfig aliceAgain = namespacedConfig("assistant", "alice");
		RunnableConfig bob = namespacedConfig("assistant", "bob");

		saver.put(alice, checkpoint("ALICE"));
		saver.put(bob, checkpoint("BOB"));

		assertThat(saver.checkpointThreadId(alice)).isNotEqualTo(BaseCheckpointSaver.THREAD_ID_DEFAULT);
		assertThat(saver.get(aliceAgain).orElseThrow().getState()).containsEntry("owner", "ALICE");
		assertThat(saver.get(bob).orElseThrow().getState()).containsEntry("owner", "BOB");
	}

	@Test
	void strictPolicyAlsoRejectsMissingThreadIdWhenNamespacedMetadataIsPresent() {
		MemorySaver saver = MemorySaver.builder().build();
		CheckpointThreadIdPolicy.setRequireExplicitThreadId(true);

		assertThatThrownBy(() -> saver.put(namespacedConfig("assistant", "alice"), checkpoint("ALICE")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("threadId");
	}

	@Test
	void sharedThreadNamespaceRuleAppliesAcrossSaverImplementations() throws Exception {
		VersionedMemoryCheckpointSaver saver = new VersionedMemoryCheckpointSaver();
		RunnableConfig callerA = RunnableConfig.builder().build();
		RunnableConfig callerB = RunnableConfig.builder().build();

		saver.put(callerA, checkpoint("USER_A"));

		assertThat(saver.get(callerB)).isPresent();
		assertThat(saver.get(callerB).orElseThrow().getState())
				.containsEntry("owner", "USER_A")
				.containsEntry("secret", "user_a-private");
	}

	private static Checkpoint checkpoint(String owner) {
		return Checkpoint.builder()
				.id(owner + "-checkpoint")
				.state(Map.of("owner", owner, "secret", owner.toLowerCase() + "-private"))
				.nodeId("node-a")
				.nextNodeId(StateGraph.END)
				.build();
	}

	private static RunnableConfig namespacedConfig(String appName, String userId) {
		return RunnableConfig.builder()
				.addMetadata(RunnableConfig.APP_NAME_METADATA_KEY, appName)
				.addMetadata(RunnableConfig.USER_ID_METADATA_KEY, userId)
				.build();
	}

}
