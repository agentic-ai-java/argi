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

import io.github.agentic.ai.graph.agent.extension.file.FilesystemBackend;

import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.graph.agent.tool.TypedTool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool for finding files matching a glob pattern.
 */
public class GlobTool implements BiFunction<String, ToolContext, String> {

	private final FilesystemBackend backend;

	public static final String DESCRIPTION = """
			Find files matching a glob pattern.

			Usage:
			- Supports standard glob patterns: `*` (any characters), `**` (any directories), `?` (single character)
			- Returns a list of absolute file paths that match the pattern

			Examples:
			- `**/*.java` - Find all Java files
			- `*.txt` - Find all text files in root
			- `/src/**/*.xml` - Find all XML files under /src
			""";

	public GlobTool() {
		this(null);
	}

	public GlobTool(FilesystemBackend backend) {
		this.backend = backend;
	}

	@Override
	public String apply(String pattern, ToolContext toolContext) {
		return glob(pattern, toolContext);
	}

	private String glob(String pattern, ToolContext toolContext) {
		try {
			if (this.backend != null) {
				return ListFilesTool.formatFileInfoPaths(this.backend.globInfo(pattern, "/"),
						"No files found matching pattern: " + pattern);
			}
			Path basePathObj = Paths.get(System.getProperty("user.dir"));
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

			List<String> matchedFiles = new ArrayList<>();

			Files.walk(basePathObj).filter(Files::isRegularFile).filter(path -> {
				Path relativePath = basePathObj.relativize(path);
				return matcher.matches(relativePath) || matcher.matches(path);
			}).forEach(path -> matchedFiles.add(path.toString()));

			if (matchedFiles.isEmpty()) {
				return "No files found matching pattern: " + pattern;
			}

			return String.join("\n", matchedFiles);
		}
		catch (IOException e) {
			return "Error searching for files: " + e.getMessage();
		}
	}

	public static Tool createGlobTool(String description) {
		return createGlobTool(description, null);
	}

	public static Tool createGlobTool(String description, FilesystemBackend backend) {
		BiFunction<GlobRequest, ToolContext, String> function = (request, context) -> new GlobTool(backend)
			.apply(request.pattern, context);
		return TypedTool.builder("glob", function).description(description).inputType(GlobRequest.class).build();
	}

	/** Request structure for glob searches. */
	public static class GlobRequest {

		@JsonProperty(required = true)
		@JsonPropertyDescription("The glob pattern to match files")
		public String pattern;

		public GlobRequest() {
		}

		public GlobRequest(String pattern) {
			this.pattern = pattern;
		}

	}

}
