/*
 * Copyright 2025-2026 the original author or authors.
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

package io.github.agentic.ai.graph.agent.tool.catalog;

import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolRegistry;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable in-process reference {@link ToolCatalogProvider}.
 *
 * <p>
 * Every registered {@code (name, version)} stays resolvable; the version registered last
 * for a name is the one advertised by {@link #summaries()}. This lets a run that was
 * offered an older version keep resolving exactly that version after a newer one is
 * published. Registering the same {@code (name, version)} twice is rejected.
 * </p>
 */
public final class InMemoryToolCatalogProvider implements ToolCatalogProvider {

	private final String providerId;

	private final Map<ToolId, Map<String, ToolCatalogEntry>> versions;

	private final List<ToolSummary> summaries;

	private final String revision;

	private InMemoryToolCatalogProvider(Builder builder) {
		this.providerId = builder.providerId;
		Map<ToolId, Map<String, ToolCatalogEntry>> copy = new LinkedHashMap<>();
		builder.versions.forEach((id, byVersion) -> copy.put(id, Map.copyOf(byVersion)));
		this.versions = Map.copyOf(copy);
		this.summaries = builder.current.values().stream().map(ToolCatalogEntry::summary).toList();
		this.revision = builder.revision != null ? builder.revision
				: Integer.toHexString(this.summaries.stream()
					.map(summary -> summary.id().qualifiedName() + "@" + summary.version())
					.toList()
					.hashCode());
	}

	public static Builder builder(String providerId) {
		return new Builder(providerId);
	}

	/**
	 * Adapts an existing immutable {@link ToolRegistry} into a single-version provider,
	 * using each tool description as its summary.
	 * @param providerId the provider id
	 * @param version the version assigned to every tool
	 * @param registry the registry to expose
	 * @return the provider
	 */
	public static InMemoryToolCatalogProvider fromRegistry(String providerId, String version, ToolRegistry registry) {
		Builder builder = builder(providerId);
		for (ToolDefinition definition : registry.definitions()) {
			builder.tool(registry.find(definition.name()).orElseThrow(), version);
		}
		return builder.build();
	}

	@Override
	public String providerId() {
		return this.providerId;
	}

	@Override
	public List<ToolSummary> summaries() {
		return this.summaries;
	}

	@Override
	public Optional<ToolCatalogEntry> resolve(ToolId id, String version) {
		Map<String, ToolCatalogEntry> byVersion = this.versions.get(id);
		return byVersion == null ? Optional.empty() : Optional.ofNullable(byVersion.get(version));
	}

	@Override
	public Optional<String> revision() {
		return Optional.of(this.revision);
	}

	public static final class Builder {

		private final String providerId;

		private final Map<ToolId, Map<String, ToolCatalogEntry>> versions = new LinkedHashMap<>();

		private final Map<ToolId, ToolCatalogEntry> current = new LinkedHashMap<>();

		private String revision;

		private Builder(String providerId) {
			this.providerId = ToolId.requireProvider(providerId);
		}

		/** Registers a tool version, using the tool description as its summary. */
		public Builder tool(Tool tool, String version) {
			Objects.requireNonNull(tool, "tool must not be null");
			return tool(tool, version, tool.definition().description(), null);
		}

		/**
		 * Registers a tool version. The provider-local tool name is the tool's definition
		 * name.
		 * @param tool the executable binding for this version
		 * @param version the version
		 * @param summary the lightweight summary
		 * @param domain optional domain
		 * @param tags optional tags
		 * @return this builder
		 */
		public Builder tool(Tool tool, String version, String summary, String domain, String... tags) {
			Objects.requireNonNull(tool, "tool must not be null");
			ToolId id = ToolId.of(this.providerId, tool.definition().name());
			ToolSummary toolSummary = new ToolSummary(id, version, summary, domain,
					tags == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(tags)));
			ToolCatalogEntry entry = new ToolCatalogEntry(toolSummary, tool);
			Map<String, ToolCatalogEntry> byVersion = this.versions.computeIfAbsent(id, key -> new LinkedHashMap<>());
			if (byVersion.putIfAbsent(version, entry) != null) {
				throw new IllegalArgumentException("Tool version already registered: " + id + "@" + version);
			}
			this.current.put(id, entry);
			return this;
		}

		/** Overrides the computed summary revision, for example with a deployment id. */
		public Builder revision(String revision) {
			this.revision = revision;
			return this;
		}

		public InMemoryToolCatalogProvider build() {
			return new InMemoryToolCatalogProvider(this);
		}

	}

}
