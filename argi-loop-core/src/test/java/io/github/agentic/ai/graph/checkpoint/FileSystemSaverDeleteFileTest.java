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

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.savers.file.FileSystemSaver;
import io.github.agentic.ai.graph.serializer.std.ObjectStreamStateSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FileSystemSaver#deleteFile(RunnableConfig)}.
 */
public class FileSystemSaverDeleteFileTest {

	@Test
	public void deleteFileShouldRemoveExistingCheckpoint() throws Exception {
		Path root = Files.createTempDirectory("checkpoint");
		FileSystemSaver saver = FileSystemSaver.builder()
				.targetFolder(root)
				.stateSerializer(new ObjectStreamStateSerializer(OverAllState::new))
				.build();
		RunnableConfig config = RunnableConfig.builder().threadId("t1").build();

		Path checkpointFile = root.resolve("thread-t1.saver");
		Files.createFile(checkpointFile);

		assertTrue(Files.exists(checkpointFile));
		assertTrue(saver.deleteFile(config));
		assertFalse(Files.exists(checkpointFile));
		assertFalse(saver.deleteFile(config));
	}

	@Test
	public void releaseShouldTreatThreadIdAsLiteralWhenSelectingBackupVersion() throws Exception {
		Path root = Files.createTempDirectory("checkpoint-regex");
		FileSystemSaver saver = FileSystemSaver.builder()
				.targetFolder(root)
				.stateSerializer(new ObjectStreamStateSerializer(OverAllState::new))
				.build();
		RunnableConfig config = RunnableConfig.builder().threadId("id[abc]").build();

		saver.put(config, Checkpoint.builder()
				.state(Map.of("value", "current"))
				.nodeId("node")
				.nextNodeId("end")
				.build());
		Files.createFile(root.resolve("thread-ida-v9.saver"));

		var tag = saver.release(config);

		assertEquals("id[abc]", tag.threadId());
		assertTrue(Files.exists(root.resolve("thread-id[abc]-v1.saver")));
		assertFalse(Files.exists(root.resolve("thread-id[abc].saver")));
		assertTrue(Files.exists(root.resolve("thread-ida-v9.saver")));
	}

}
