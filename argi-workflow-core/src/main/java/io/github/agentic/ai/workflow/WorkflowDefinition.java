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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, version-bound workflow definition shared by API, runtime and future UI. */
public record WorkflowDefinition(String id, String version, List<WorkflowStep> steps, String inputSchema,
		String outputSchema, Map<String, Object> metadata) {

	public WorkflowDefinition {
		WorkflowValidation.identifier(id, "workflow id");
		WorkflowValidation.version(version, "workflow version");
		steps = List.copyOf(steps);
		if (steps.isEmpty()) {
			throw new IllegalArgumentException("workflow must contain at least one step");
		}
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	public WorkflowDefinition(String id, String version, List<WorkflowStep> steps) {
		this(id, version, steps, null, null, Map.of());
	}

	public void validate() {
		WorkflowDefinitionValidator.validate(this);
	}

	public WorkflowDefinition withMetadata(String key, Object value) {
		Objects.requireNonNull(key, "key must not be null");
		Map<String, Object> copy = new java.util.LinkedHashMap<>(metadata);
		copy.put(key, value);
		return new WorkflowDefinition(id, version, steps, inputSchema, outputSchema, copy);
	}

}
