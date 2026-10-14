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

import io.github.agentic.ai.graph.agent.extension.file.EditResult;
import io.github.agentic.ai.graph.agent.extension.file.FileInfo;
import io.github.agentic.ai.graph.agent.extension.file.LocalFilesystemBackend;
import io.github.agentic.ai.graph.agent.extension.file.WriteResult;

import io.github.agentic.ai.api.tool.ToolContext;

import java.util.List;

/**
 * Provider-neutral filesystem tools.
 *
 * This class provides file system operations as typed tools: - read_file: Read file
 * content with pagination - write_file: Write content to a file - edit_file: Edit file by
 * replacing string occurrences - list_files: List files and directories
 *
 * This implementation is based on LocalFilesystemBackend with explicit executable
 * declarations.
 */
public class FileSystemTools {

	private static final int DEFAULT_MAX_FILE_SIZE_MB = 10;

	private final LocalFilesystemBackend backend;

	/**
	 * Default constructor using current working directory.
	 */
	public FileSystemTools() {
		this(null, false, DEFAULT_MAX_FILE_SIZE_MB);
	}

	/**
	 * Constructor with custom root directory.
	 * @param rootDir Optional root directory for file operations
	 * @param virtualMode When true, treat incoming paths as virtual absolute paths under
	 * cwd
	 * @param maxFileSizeMb Maximum file size in MB for reading operations
	 */
	public FileSystemTools(String rootDir, boolean virtualMode, int maxFileSizeMb) {
		this.backend = new LocalFilesystemBackend(rootDir, virtualMode, maxFileSizeMb);
	}

	// @formatter:off
	public String readFile(
		String filePath,
		Integer offset,
		Integer limit,
		ToolContext toolContext) { // @formatter:on

		try {
			return this.backend.read(filePath, offset != null ? offset : 0, limit != null ? limit : 500);
		}
		catch (Exception e) {
			return "Error reading file '" + filePath + "': " + e.getMessage();
		}
	}

	// @formatter:off
	public String writeFile(
		String filePath,
		String content,
		ToolContext toolContext) { // @formatter:on

		try {
			WriteResult result = this.backend.write(filePath, content);
			if (result.getError() != null) {
				return result.getError();
			}
			return "Successfully created file: " + result.getPath();
		}
		catch (Exception e) {
			return "Error writing file '" + filePath + "': " + e.getMessage();
		}
	}

	// @formatter:off
	public String editFile(
		String filePath,
		String oldString,
		String newString,
		Boolean replaceAll,
		ToolContext toolContext) { // @formatter:on

		try {
			boolean replaceAllFlag = Boolean.TRUE.equals(replaceAll);
			EditResult result = this.backend.edit(filePath, oldString, newString, replaceAllFlag);
			if (result.getError() != null) {
				return result.getError();
			}
			return String.format("Successfully edited file: %s (replaced %d occurrence(s))", result.getPath(),
					result.getOccurrences());
		}
		catch (Exception e) {
			return "Error editing file '" + filePath + "': " + e.getMessage();
		}
	}

	// @formatter:off
	public String listFiles(
		String path,
		ToolContext toolContext) { // @formatter:on

		try {
			if (!this.backend.isDirectory(path)) {
				return "Error: Directory not found: " + path;
			}
			List<FileInfo> results = this.backend.lsInfo(path);

			// Format output
			StringBuilder result = new StringBuilder();
			for (FileInfo info : results) {
				result.append(info.getPath());
				if (info.getSize() != null) {
					result.append(" (").append(info.getSize()).append(" bytes)");
				}
				if (info.getModifiedAt() != null) {
					result.append(" [").append(info.getModifiedAt()).append("]");
				}
				result.append("\n");
			}

			return !result.isEmpty() ? result.toString().trim() : "Directory is empty";
		}
		catch (Exception e) {
			return "Error listing directory '" + path + "': " + e.getMessage();
		}
	}

	/** Register all filesystem operations against this instance's configured backend. */
	public List<io.github.agentic.ai.api.tool.Tool> tools() {
		return List.of(
				io.github.agentic.ai.graph.agent.tool.TypedTool
					.builder("read_file",
							(ReadRequest r, ToolContext c) -> readFile(r.filePath(), r.offset(), r.limit(), c))
					.description("Read a file with line pagination")
					.inputType(ReadRequest.class)
					.build(),
				io.github.agentic.ai.graph.agent.tool.TypedTool
					.builder("write_file", (WriteRequest r, ToolContext c) -> writeFile(r.filePath(), r.content(), c))
					.description("Create a new file")
					.inputType(WriteRequest.class)
					.build(),
				io.github.agentic.ai.graph.agent.tool.TypedTool
					.builder("edit_file",
							(EditRequest r, ToolContext c) -> editFile(r.filePath(), r.oldString(), r.newString(),
									r.replaceAll(), c))
					.description("Replace text in a file")
					.inputType(EditRequest.class)
					.build(),
				io.github.agentic.ai.graph.agent.tool.TypedTool
					.builder("list_files", (ListRequest r, ToolContext c) -> listFiles(r.path(), c))
					.description("List files and directories")
					.inputType(ListRequest.class)
					.build());
	}

	public record ReadRequest(String filePath, Integer offset, Integer limit) {
	}

	public record WriteRequest(String filePath, String content) {
	}

	public record EditRequest(String filePath, String oldString, String newString, Boolean replaceAll) {
	}

	public record ListRequest(String path) {
	}

	// Helper methods

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String rootDir;

		private boolean virtualMode = false;

		private int maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB;

		public Builder rootDir(String rootDir) {
			this.rootDir = rootDir;
			return this;
		}

		public Builder virtualMode(boolean virtualMode) {
			this.virtualMode = virtualMode;
			return this;
		}

		public Builder maxFileSizeMb(int maxFileSizeMb) {
			this.maxFileSizeMb = maxFileSizeMb;
			return this;
		}

		public FileSystemTools build() {
			return new FileSystemTools(rootDir, virtualMode, maxFileSizeMb);
		}

	}

}
