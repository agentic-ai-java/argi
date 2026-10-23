/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory immutable-version registry; replacing a published version is rejected. */
public final class WorkflowRegistry {

	private final Map<String, Map<String, WorkflowDefinition>> definitions = new ConcurrentHashMap<>();

	public WorkflowDefinition publish(WorkflowDefinition definition) {
		Objects.requireNonNull(definition, "definition must not be null");
		definition.validate();
		Map<String, WorkflowDefinition> versions = definitions.computeIfAbsent(definition.id(), ignored ->
				new ConcurrentHashMap<>());
		WorkflowDefinition existing = versions.putIfAbsent(definition.version(), definition);
		if (existing != null && !existing.equals(definition)) {
			throw new IllegalStateException("workflow version is already published: " + definition.id() + "@"
					+ definition.version());
		}
		return existing == null ? definition : existing;
	}

	public WorkflowDefinition get(String id, String version) {
		Map<String, WorkflowDefinition> versions = definitions.get(id);
		if (versions == null || !versions.containsKey(version)) {
			throw new IllegalArgumentException("workflow version is not published: " + id + "@" + version);
		}
		return versions.get(version);
	}

	public WorkflowDefinition latest(String id) {
		Map<String, WorkflowDefinition> versions = definitions.get(id);
		if (versions == null || versions.isEmpty()) {
			throw new IllegalArgumentException("workflow is not published: " + id);
		}
		return new TreeMap<>(versions).lastEntry().getValue();
	}

}
