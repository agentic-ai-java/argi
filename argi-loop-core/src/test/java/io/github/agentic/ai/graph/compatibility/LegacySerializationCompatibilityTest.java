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
package io.github.agentic.ai.graph.compatibility;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.graph.store.StoreItem;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySerializationCompatibilityTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void readsLegacyCheckpointFixtureAndWritesTheSameJsonSemantics() throws IOException {
		Checkpoint checkpoint = mapper.readValue(resource("checkpoint.json"), Checkpoint.class);

		assertThat(checkpoint.getId()).isEqualTo("checkpoint-2.1.0");
		assertThat(checkpoint.getState()).containsEntry("counter", 7).containsEntry("owner", "legacy-user");
		assertThat(checkpoint.getNodeId()).isEqualTo("legacy-node");
		assertThat(checkpoint.getNextNodeId()).isEqualTo("legacy-next");
		assertThat(mapper.readTree(mapper.writeValueAsBytes(checkpoint)))
			.isEqualTo(mapper.readTree(resource("checkpoint.json")));
	}

	@Test
	void readsLegacyStoreItemFixtureAndWritesTheSameJsonSemantics() throws IOException {
		StoreItem item = mapper.readValue(resource("store-item.json"), StoreItem.class);

		assertThat(item.getNamespace()).containsExactly("users", "legacy-user", "preferences");
		assertThat(item.getKey()).isEqualTo("ui-settings");
		assertThat(item.getValue()).containsEntry("language", "en-US").containsEntry("theme", "dark");
		assertThat(item.getCreatedAt()).isEqualTo(1700000000000L);
		assertThat(item.getUpdatedAt()).isEqualTo(1700000001000L);
		assertThat(mapper.readTree(mapper.writeValueAsBytes(item))).isEqualTo(mapper.readTree(resource("store-item.json")));
	}

	@Test
	void checkpointThreadIdUsesStableScopedNamespaceEncoding() {
		RunnableConfig config = RunnableConfig.builder()
			.threadId("thread")
			.addMetadata(RunnableConfig.APP_NAME_METADATA_KEY, "app")
			.addMetadata(RunnableConfig.USER_ID_METADATA_KEY, "user")
			.build();

		assertThat(MemorySaver.builder().build().checkpointThreadId(config)).isEqualTo("ns-YXBw.dXNlcg.dGhyZWFk");
	}

	@Test
	void checkpointThreadIdKeepsLegacyUnscopedThreadMapping() {
		RunnableConfig config = RunnableConfig.builder().threadId("thread").build();

		assertThat(MemorySaver.builder().build().checkpointThreadId(config)).isEqualTo("thread");
	}

	private InputStream resource(String name) {
		String path = "compatibility/2.1.0/" + name;
		InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
		assertThat(stream).as("compatibility fixture %s must exist", path).isNotNull();
		return stream;
	}

}
