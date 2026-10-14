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
import io.github.agentic.ai.graph.agent.extension.file.GrepMatch;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool for searching text patterns in files.
 */
public class GrepTool implements BiFunction<GrepTool.GrepRequest, ToolContext, String> {

	private final FilesystemBackend backend;

	public static final String DESCRIPTION = """
			Search for a pattern in files.

			Usage:
			- The pattern parameter is the text to search for (literal string, not regex)
			- The path parameter filters which directory to search in
			- The glob parameter accepts a glob pattern to filter which files to search

			Examples:
			- Search all files: `grep(pattern="TODO")`
			- The search is case-sensitive by default.
			""";

	public GrepTool() {
		this(null);
	}

	public GrepTool(FilesystemBackend backend) {
		this.backend = backend;
	}

	@Override
	public String apply(GrepRequest request, ToolContext toolContext) {
		try {
			if (this.backend != null) {
				return formatBackendGrepResult(this.backend.grepRaw(request.pattern, request.path, request.glob),
						request.pattern, request.outputMode);
			}
			Path searchPath = request.path != null ? Paths.get(request.path)
					: Paths.get(System.getProperty("user.dir"));

			List<String> results = new ArrayList<>();
			PathMatcher globMatcher = request.glob != null
					? FileSystems.getDefault().getPathMatcher("glob:" + request.glob) : null;

			Files.walk(searchPath)
				.filter(Files::isRegularFile)
				.filter(path -> globMatcher == null || globMatcher.matches(path.getFileName()))
				.forEach(path -> {
					try {
						List<String> lines = Files.readAllLines(path);
						for (int i = 0; i < lines.size(); i++) {
							if (lines.get(i).contains(request.pattern)) {
								String result = switch (request.outputMode) {
									case "files_with_matches" -> path.toString();
									case "content" -> path + ":" + (i + 1) + ": " + lines.get(i);
									case "count" -> path + ": matched";
									default -> path.toString();
								};
								results.add(result);
								if ("files_with_matches".equals(request.outputMode)) {
									break; // Only need file name once
								}
							}
						}
					}
					catch (IOException e) {
						// Skip files that can't be read
					}
				});

			if (results.isEmpty()) {
				return "No matches found for pattern: " + request.pattern;
			}

			return String.join("\n", results);
		}
		catch (IOException e) {
			return "Error searching files: " + e.getMessage();
		}
	}

	public static Tool createGrepTool(String description) {
		return createGrepTool(description, null);
	}

	public static Tool createGrepTool(String description, FilesystemBackend backend) {
		return TypedTool.builder("grep", new GrepTool(backend))
			.description(description)
			.inputType(GrepRequest.class)
			.build();
	}

	@SuppressWarnings("unchecked")
	private static String formatBackendGrepResult(Object rawResult, String pattern, String outputMode) {
		if (rawResult instanceof String error) {
			return error;
		}
		List<GrepMatch> matches = rawResult instanceof List<?> ? (List<GrepMatch>) rawResult : List.of();
		if (matches.isEmpty()) {
			return "No matches found for pattern: " + pattern;
		}
		if ("content".equals(outputMode)) {
			List<String> results = new ArrayList<>();
			for (GrepMatch match : matches) {
				results.add(match.getPath() + ":" + match.getLine() + ": " + match.getText());
			}
			return String.join("\n", results);
		}
		if ("count".equals(outputMode)) {
			List<String> results = new ArrayList<>();
			for (GrepMatch match : matches) {
				results.add(match.getPath() + ": matched");
			}
			return String.join("\n", results);
		}
		Set<String> paths = new LinkedHashSet<>();
		for (GrepMatch match : matches) {
			paths.add(match.getPath());
		}
		return String.join("\n", paths);
	}

	/**
	 * Request structure for grep search.
	 */
	public static class GrepRequest {

		@JsonProperty(required = true)
		@JsonPropertyDescription("The text pattern to search for")
		public String pattern;

		@JsonProperty(value = "path")
		@JsonPropertyDescription("The directory path to search in (default: base path)")
		public String path;

		@JsonProperty(value = "glob")
		@JsonPropertyDescription("File pattern to filter which files to search (e.g., '*.java')")
		public String glob;

		@JsonProperty(value = "output_mode")
		@JsonPropertyDescription("Output format: 'files_with_matches', 'content', or 'count' (default: 'files_with_matches')")
		public String outputMode = "files_with_matches";

		public GrepRequest() {
		}

		public GrepRequest(String pattern, String path, String glob, String outputMode) {
			this.pattern = pattern;
			this.path = path;
			this.glob = glob;
			this.outputMode = outputMode;
		}

	}

}
