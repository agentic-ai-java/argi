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

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/** Permission names that may be delegated to a local child execution. */
public record DelegationScope(Set<String> permissions) {

	private static final DelegationScope NONE = new DelegationScope(Set.of());

	public DelegationScope {
		requireNonNull(permissions, "permissions cannot be null");
		TreeSet<String> copy = new TreeSet<>();
		for (String permission : permissions) {
			if (permission == null || permission.isBlank()) {
				throw new IllegalArgumentException("permission must not be blank");
			}
			copy.add(permission);
		}
		permissions = Collections.unmodifiableSet(copy);
	}

	public static DelegationScope of(Set<String> permissions) {
		return permissions == null || permissions.isEmpty() ? none() : new DelegationScope(permissions);
	}

	public static DelegationScope none() {
		return NONE;
	}

	public boolean allows(String permission) {
		return permission != null && this.permissions.contains(permission);
	}

	public DelegationScope narrowTo(Set<String> requestedPermissions) {
		requireNonNull(requestedPermissions, "requested permissions cannot be null");
		if (!this.permissions.containsAll(requestedPermissions)) {
			throw new IllegalArgumentException("delegated permission is not present in the parent scope");
		}
		return of(requestedPermissions);
	}
}
