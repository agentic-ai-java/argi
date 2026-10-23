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

final class WorkflowValidation {

	private WorkflowValidation() {
	}

	static void identifier(String value, String name) {
		if (value == null || value.isBlank() || !value.matches("[A-Za-z][A-Za-z0-9_.:-]*")) {
			throw new IllegalArgumentException(name + " must be a non-blank identifier");
		}
	}

	static void version(String value, String name) {
		if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
			throw new IllegalArgumentException(name + " must be a non-blank version");
		}
	}

	static void path(String value, String name) {
		if (value == null || value.isBlank() || value.startsWith(".") || value.endsWith(".")
				|| value.contains("..")) {
			throw new IllegalArgumentException(name + " must be a dot-separated path");
		}
	}

	static void optionalPath(String value, String name) {
		if (value != null) {
			path(value, name);
		}
	}

}
