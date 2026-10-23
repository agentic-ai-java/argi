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

import java.util.List;

/**
 * Durable mode was requested but a runtime component cannot be rebuilt from committed
 * data, or was not declared at all.
 *
 * <p>
 * This is a fail-closed gate, not a warning: an undeclared role is treated as a missing
 * recovery capability.
 */
public final class RecoveryCapabilityException extends IllegalStateException {

	private final DefinitionFailureCode code;

	private final List<RuntimeComponentDeclaration> offending;

	private final List<RuntimeComponentRole> missingRoles;

	private RecoveryCapabilityException(DefinitionFailureCode code, List<RuntimeComponentDeclaration> offending,
			List<RuntimeComponentRole> missingRoles, String message) {
		super(message);
		this.code = code;
		this.offending = List.copyOf(offending);
		this.missingRoles = List.copyOf(missingRoles);
	}

	/** At least one declared component depends on the admitting JVM instance. */
	public static RecoveryCapabilityException notRecoverable(List<RuntimeComponentDeclaration> offending) {
		List<RuntimeComponentDeclaration> components = List.copyOf(offending);
		return new RecoveryCapabilityException(DefinitionFailureCode.COMPONENT_NOT_RECOVERABLE, components, List.of(),
				"Durable mode requires data-only recovery, but these components are instance-bound: "
						+ components.stream().map(RuntimeComponentDeclaration::display).toList());
	}

	/** Durable mode was requested without a declaration for every component role. */
	public static RecoveryCapabilityException missingRoles(List<RuntimeComponentRole> missingRoles) {
		List<RuntimeComponentRole> roles = List.copyOf(missingRoles);
		return new RecoveryCapabilityException(DefinitionFailureCode.COMPONENT_NOT_RECOVERABLE, List.of(), roles,
				"Durable mode requires an explicit recovery declaration for every runtime component role; missing: "
						+ roles.stream().map(RuntimeComponentRole::wireName).toList());
	}

	public DefinitionFailureCode code() {
		return this.code;
	}

	/** Components that declared {@link RecoveryCapability#INSTANCE_BOUND}. */
	public List<RuntimeComponentDeclaration> offending() {
		return this.offending;
	}

	/** Roles with no declaration at all. */
	public List<RuntimeComponentRole> missingRoles() {
		return this.missingRoles;
	}

}
