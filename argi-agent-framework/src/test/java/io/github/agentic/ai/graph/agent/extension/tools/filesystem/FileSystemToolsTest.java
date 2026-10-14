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
package io.github.agentic.ai.graph.agent.extension.tools.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.agentic.ai.api.tool.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemToolsTest {

	@TempDir
	Path root;

	@Test
	void readFileRejectsIntermediateSymlinkOutsideVirtualRoot() throws Exception {
		Path outside = Files.createTempDirectory(this.root.getParent(), "outside");
		Files.writeString(outside.resolve("outside.txt"), "classified\n");
		Files.createSymbolicLink(this.root.resolve("link"), outside);

		FileSystemTools tools = new FileSystemTools(this.root.toString(), true, 10);

		String result = tools.readFile("/link/outside.txt", 0, 10, new ToolContext(Collections.emptyMap()));

		assertThat(result).contains("outside root directory");
		assertThat(result).doesNotContain("classified");
	}

	@Test
	void readFileAppliesConfiguredMaxFileSize() throws Exception {
		Files.writeString(this.root.resolve("large.txt"), "too large\n");
		FileSystemTools tools = new FileSystemTools(this.root.toString(), true, 0);

		String result = tools.readFile("/large.txt", 0, 10, new ToolContext(Collections.emptyMap()));

		assertThat(result).contains("exceeds maximum file size");
	}

	@Test
	void listFilesDistinguishesMissingDirectoryFromEmptyDirectory() {
		FileSystemTools tools = new FileSystemTools(this.root.toString(), true, 10);

		String result = tools.listFiles("/missing", new ToolContext(Collections.emptyMap()));

		assertThat(result).isEqualTo("Error: Directory not found: /missing");
	}

}
