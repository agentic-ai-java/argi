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

import static java.util.Objects.requireNonNull;

/**
 * An application's explicit statement about how one runtime component recovers.
 *
 * <p>
 * The declaration is authored by the application, not inferred by the runtime, because
 * only the application knows whether a component holds instance state. {@code id} names
 * the concrete component for diagnostics.
 */
public record RuntimeComponentDeclaration(RuntimeComponentRole role, String id, RecoveryCapability capability) {

	public RuntimeComponentDeclaration {
		requireNonNull(role, "role cannot be null");
		requireNonNull(capability, "capability cannot be null");
		if (capability == RecoveryCapability.NOT_APPLICABLE) {
			if (id == null || id.isBlank()) {
				id = "none";
			}
		}
		else {
			DefinitionValidation.text(id, "id");
		}
	}

	/** A component whose definition and state are committed as versioned data. */
	public static RuntimeComponentDeclaration dataOnly(RuntimeComponentRole role, String id) {
		return new RuntimeComponentDeclaration(role, id, RecoveryCapability.DATA_ONLY);
	}

	/** A component that needs the admitting JVM instance. */
	public static RuntimeComponentDeclaration instanceBound(RuntimeComponentRole role, String id) {
		return new RuntimeComponentDeclaration(role, id, RecoveryCapability.INSTANCE_BOUND);
	}

	/** An unused role; still declared, so "not thought about" and "not used" stay distinct. */
	public static RuntimeComponentDeclaration notApplicable(RuntimeComponentRole role) {
		return new RuntimeComponentDeclaration(role, null, RecoveryCapability.NOT_APPLICABLE);
	}

	public boolean recoverable() {
		return this.capability != RecoveryCapability.INSTANCE_BOUND;
	}

	public String display() {
		return this.role.wireName() + "[" + this.id + "]=" + this.capability;
	}

}
