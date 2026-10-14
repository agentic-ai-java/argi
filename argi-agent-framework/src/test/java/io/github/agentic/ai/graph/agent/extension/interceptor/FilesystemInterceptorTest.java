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
package io.github.agentic.ai.graph.agent.extension.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.graph.agent.extension.file.LocalFilesystemBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.chat.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemInterceptorTest {

	@TempDir
	Path root;

	@Test
	void defaultBackendDoesNotExposeHostAbsolutePaths() {
		FilesystemInterceptor interceptor = FilesystemInterceptor.builder().readOnly(true).build();

		String result = execute(interceptor, "read_file", "{\"file_path\":\"/etc/passwd\",\"offset\":0,\"limit\":10}",
				new ToolContext(Collections.emptyMap()));

		assertFalse(result.contains("root:"));
		assertTrue(result.contains("not found"));
	}

	@Test
	void backendKeepsReadToolInsideVirtualRoot() throws Exception {
		Files.writeString(this.root.resolve("allowed.txt"), "inside\n");

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.readOnly(true)
			.build();

		String inside = execute(interceptor, "read_file", "{\"file_path\":\"/allowed.txt\",\"offset\":0,\"limit\":10}",
				new ToolContext(Collections.emptyMap()));
		String traversal = execute(interceptor, "read_file",
				"{\"file_path\":\"../allowed.txt\",\"offset\":0,\"limit\":10}",
				new ToolContext(Collections.emptyMap()));

		assertTrue(inside.contains("inside"));
		assertTrue(traversal.contains("Path traversal not allowed"));
	}

	@Test
	void backendKeepsWriteToolInsideVirtualRoot() throws Exception {
		Path escaped = this.root.getParent().resolve("escape.txt");
		Files.deleteIfExists(escaped);

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.build();

		String created = execute(interceptor, "write_file", "{\"file_path\":\"/sub/new.txt\",\"content\":\"inside\"}",
				new ToolContext(Collections.emptyMap()));
		String traversal = execute(interceptor, "write_file",
				"{\"file_path\":\"../escape.txt\",\"content\":\"outside\"}", new ToolContext(Collections.emptyMap()));

		assertTrue(created.contains("Successfully created file"));
		assertTrue(Files.readString(this.root.resolve("sub/new.txt")).contains("inside"));
		assertTrue(traversal.contains("Path traversal not allowed"));
		assertFalse(Files.exists(escaped));
	}

	@Test
	void backendKeepsListGlobAndGrepToolsInsideVirtualRoot() throws Exception {
		Files.createDirectories(this.root.resolve("dir"));
		Files.writeString(this.root.resolve("dir/inside.txt"), "needle\n");

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.readOnly(true)
			.build();

		String listed = execute(interceptor, "ls", "{\"path\":\"/\"}", new ToolContext(Collections.emptyMap()));
		String globbed = execute(interceptor, "glob", "{\"pattern\":\"**/*.txt\"}",
				new ToolContext(Collections.emptyMap()));
		String grep = execute(interceptor, "grep",
				"{\"pattern\":\"needle\",\"path\":\"/\",\"glob\":\"*.txt\",\"output_mode\":\"content\"}",
				new ToolContext(Collections.emptyMap()));

		assertTrue(listed.contains("/dir/"));
		assertTrue(globbed.contains("/dir/inside.txt"));
		assertTrue(grep.contains("/dir/inside.txt:1: needle"));
	}

	@Test
	void filesystemListAndGlobCallbacksExposeObjectSchemas() throws Exception {
		FilesystemInterceptor interceptor = FilesystemInterceptor.builder().readOnly(true).build();
		ObjectMapper objectMapper = new ObjectMapper();

		JsonNode lsSchema = objectMapper.readTree(tool(interceptor, "ls").definition().inputSchema());
		JsonNode globSchema = objectMapper.readTree(tool(interceptor, "glob").definition().inputSchema());

		assertEquals("object", lsSchema.path("type").asText());
		assertTrue(lsSchema.path("properties").has("path"));
		assertEquals("object", globSchema.path("type").asText());
		assertTrue(globSchema.path("properties").has("pattern"));
	}

	@Test
	void listToolDistinguishesMissingFileAndEmptyDirectory() throws Exception {
		Files.writeString(this.root.resolve("plain.txt"), "content");
		Files.createDirectory(this.root.resolve("empty"));
		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.readOnly(true)
			.build();

		String missing = execute(interceptor, "ls", "{\"path\":\"/missing\"}", new ToolContext(Collections.emptyMap()));
		String file = execute(interceptor, "ls", "{\"path\":\"/plain.txt\"}", new ToolContext(Collections.emptyMap()));
		String empty = execute(interceptor, "ls", "{\"path\":\"/empty\"}", new ToolContext(Collections.emptyMap()));

		assertThat(missing).contains("Error: Directory not found: /missing").doesNotContain("Directory is empty");
		assertThat(file).contains("Error: Directory not found: /plain.txt").doesNotContain("Directory is empty");
		assertThat(empty).contains("Directory is empty").doesNotContain("Directory not found");
	}

	@Test
	void backendKeepsEditToolInsideVirtualRoot() throws Exception {
		Files.writeString(this.root.resolve("inside.txt"), "before\n");

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.build();

		String edited = execute(interceptor, "edit_file",
				"{\"file_path\":\"/inside.txt\",\"old_string\":\"before\",\"new_string\":\"after\",\"replace_all\":false}",
				new ToolContext(Collections.emptyMap()));
		String traversal = execute(interceptor, "edit_file",
				"{\"file_path\":\"../inside.txt\",\"old_string\":\"before\",\"new_string\":\"after\",\"replace_all\":false}",
				new ToolContext(Collections.emptyMap()));

		assertTrue(edited.contains("Successfully edited file"));
		assertTrue(Files.readString(this.root.resolve("inside.txt")).contains("after"));
		assertTrue(traversal.contains("Path traversal not allowed"));
	}

	@Test
	void backendRejectsReadThroughIntermediateSymlinkOutsideVirtualRoot() throws Exception {
		Path outside = Files.createTempDirectory(this.root.getParent(), "outside");
		Files.writeString(outside.resolve("outside.txt"), "classified\n");
		Files.createSymbolicLink(this.root.resolve("link"), outside);

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.readOnly(true)
			.build();

		String result = execute(interceptor, "read_file",
				"{\"file_path\":\"/link/outside.txt\",\"offset\":0,\"limit\":10}",
				new ToolContext(Collections.emptyMap()));

		assertThat(result).contains("outside root directory");
		assertThat(result).doesNotContain("classified");
	}

	@Test
	void backendRejectsWriteThroughIntermediateSymlinkOutsideVirtualRoot() throws Exception {
		Path outside = Files.createTempDirectory(this.root.getParent(), "outside");
		Files.createSymbolicLink(this.root.resolve("link"), outside);

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.build();

		String result = execute(interceptor, "write_file",
				"{\"file_path\":\"/link/created.txt\",\"content\":\"escaped\"}",
				new ToolContext(Collections.emptyMap()));

		assertThat(result).contains("outside root directory");
		assertThat(outside.resolve("created.txt")).doesNotExist();
	}

	@Test
	void backendRejectsEditThroughIntermediateSymlinkOutsideVirtualRoot() throws Exception {
		Path outside = Files.createTempDirectory(this.root.getParent(), "outside");
		Path outsideFile = outside.resolve("outside.txt");
		Files.writeString(outsideFile, "before\n");
		Files.createSymbolicLink(this.root.resolve("link"), outside);

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 10))
			.build();

		String result = execute(interceptor, "edit_file",
				"{\"file_path\":\"/link/outside.txt\",\"old_string\":\"before\",\"new_string\":\"after\",\"replace_all\":false}",
				new ToolContext(Collections.emptyMap()));

		assertThat(result).contains("outside root directory");
		assertThat(Files.readString(outsideFile)).isEqualTo("before\n");
	}

	@Test
	void backendAppliesMaxFileSizeToDirectReadAndEdit() throws Exception {
		Path file = this.root.resolve("large.txt");
		Files.writeString(file, "too large\n");

		FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
			.backend(new LocalFilesystemBackend(this.root.toString(), true, 0))
			.build();

		String read = execute(interceptor, "read_file", "{\"file_path\":\"/large.txt\",\"offset\":0,\"limit\":10}",
				new ToolContext(Collections.emptyMap()));
		String edit = execute(interceptor, "edit_file",
				"{\"file_path\":\"/large.txt\",\"old_string\":\"too\",\"new_string\":\"not\",\"replace_all\":false}",
				new ToolContext(Collections.emptyMap()));

		assertThat(read).contains("exceeds maximum file size");
		assertThat(edit).contains("exceeds maximum file size");
		assertThat(Files.readString(file)).isEqualTo("too large\n");
	}

	private static Tool tool(FilesystemInterceptor interceptor, String name) {
		return interceptor.getTools()
			.stream()
			.filter(tool -> name.equals(tool.definition().name()))
			.findFirst()
			.orElseThrow();
	}

	private static String execute(FilesystemInterceptor interceptor, String name, String arguments,
			ToolContext context) {
		var call = new ToolCall("test-call", name, arguments);
		return tool(interceptor, name).execute(call, context).toCompletableFuture().join().toMessage(call).text();
	}

}
