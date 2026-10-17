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

package io.github.agentic.ai.graph.execution.definition;

/**
 * A definition domain a durable run must pin by version.
 *
 * <p>
 * The wire names are stable data identifiers used by {@link DefinitionReference} and
 * persisted definition records; they are not Java class names.
 */
public enum DefinitionKind {

	/** The graph definition (nodes and edges) that executes the run. */
	GRAPH("graph"),

	/** The tool catalog and the tool input schemas offered to the model. */
	TOOL_CATALOG("tool_catalog"),

	/** The data codec used for run, task, checkpoint and journal payloads. */
	CODEC("codec"),

	/** The application definition the run belongs to. */
	APPLICATION("application");

	private final String wireName;

	DefinitionKind(String wireName) {
		this.wireName = wireName;
	}

	/** Stable persisted identifier; never a Java class name. */
	public String wireName() {
		return this.wireName;
	}

	public static DefinitionKind fromWireName(String wireName) {
		for (DefinitionKind kind : values()) {
			if (kind.wireName.equals(wireName)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("Unknown definition kind: " + wireName);
	}

}
