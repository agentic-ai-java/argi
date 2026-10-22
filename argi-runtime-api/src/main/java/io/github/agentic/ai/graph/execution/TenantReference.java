/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.execution;

/**
 * Backend-neutral tenant reference known by an application identity provider. It
 * deliberately carries no token, credential, session or client object.
 *
 * @param authority stable name of the application-owned identity authority
 * @param id opaque tenant identifier within that authority
 */
public record TenantReference(String authority, String id) {

	public TenantReference {
		requireText(authority, "authority");
		requireText(id, "id");
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
