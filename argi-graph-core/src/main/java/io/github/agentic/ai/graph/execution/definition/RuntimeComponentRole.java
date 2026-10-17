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
 * The runtime component roles whose recovery capability durable mode must verify.
 *
 * <p>
 * Each role is a known place where an implementation can quietly depend on the admitting
 * JVM. The application declares one {@link RuntimeComponentDeclaration} per role.
 */
public enum RuntimeComponentRole {

	/** An application-defined graph node. */
	CUSTOM_NODE("custom_node"),

	/** Chat or long-term memory used by the run. */
	CHAT_MEMORY("chat_memory"),

	/** The long-term store (the graph {@code Store} contract). */
	STORE("store"),

	/** A background tool or background task executor. */
	BACKGROUND_TOOL("background_tool");

	private final String wireName;

	RuntimeComponentRole(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return this.wireName;
	}

	public static RuntimeComponentRole fromWireName(String wireName) {
		for (RuntimeComponentRole role : values()) {
			if (role.wireName.equals(wireName)) {
				return role;
			}
		}
		throw new IllegalArgumentException("Unknown runtime component role: " + wireName);
	}

}
