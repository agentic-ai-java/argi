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

import io.github.agentic.ai.graph.execution.run.RunSpec;
import io.github.agentic.ai.graph.execution.run.RunStoreCapabilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * The single admission point that turns a recorded definition set into executable
 * definitions, and that gates durable mode.
 *
 * <p>
 * A recovering worker must call {@link #resolve} and only then dispatch work. Every
 * failure is raised before a definition is returned, so a missing or unsupported version
 * can never reach a tool call: the execution target, tool catalog/schema, codec and
 * application definitions are all verified first.
 *
 * <p>
 * Nothing here is invoked by {@code RunStore}, a checkpoint saver or the existing task
 * repository. Applications that do not record definitions keep their current behaviour
 * and never need a registry.
 */
public final class RunDefinitionAdmission {

	private RunDefinitionAdmission() {
	}

	/**
	 * Verify the worker and resolve every recorded definition for the admitted execution
	 * target.
	 * @throws DefinitionResolutionException if the record does not describe the run's
	 * target, the worker lacks a kind, or a recorded version is not registered
	 * @throws CodecIncompatibilityException if the envelope or the data codec is
	 * unsupported
	 */
	public static ResolvedRunDefinitions resolve(RunSpec run, RunDefinitionRecord record, DefinitionResolver resolver,
			WorkerCapabilities worker) {
		RunSpec spec = requireNonNull(run, "run cannot be null");
		RunDefinitionRecord target = requireNonNull(record, "record cannot be null");
		DefinitionReference admitted = spec.definition();
		if (!admitted.equals(target.definition())) {
			throw DefinitionResolutionException.recordMismatch(admitted, target.definition());
		}
		return resolve(target, resolver, worker);
	}

	/**
	 * Verify the worker and resolve every recorded definition.
	 * @throws DefinitionResolutionException if the worker lacks a kind or a recorded
	 * version is not registered
	 * @throws CodecIncompatibilityException if the envelope or the data codec is
	 * unsupported
	 */
	public static ResolvedRunDefinitions resolve(RunDefinitionRecord record, DefinitionResolver resolver,
			WorkerCapabilities worker) {
		RunDefinitionRecord target = requireNonNull(record, "record cannot be null");
		DefinitionResolver definitions = requireNonNull(resolver, "resolver cannot be null");
		WorkerCapabilities capabilities = requireNonNull(worker, "worker cannot be null");
		capabilities.requireDefinitionSchemaVersion(target.schemaVersion());
		EnumMap<DefinitionKind, Object> resolved = new EnumMap<>(DefinitionKind.class);
		for (DefinitionReference reference : target.references()) {
			capabilities.requireKind(reference);
			resolved.put(reference.kind(), definitions.resolveRequired(reference));
		}
		capabilities.requireCodec(target.codec());
		return new ResolvedRunDefinitions(target, resolved);
	}

	/**
	 * Fail-closed durable-mode gate.
	 *
	 * <p>
	 * Durable mode requires a store that already passes
	 * {@link RunStoreCapabilities#requireDurableMode()} and an explicit declaration for
	 * every {@link RuntimeComponentRole}; an instance-bound component, or a role that was
	 * not declared at all, is a missing recovery capability and rejects durable mode.
	 * @throws RecoveryCapabilityException if a component cannot be rebuilt from data or
	 * was not declared
	 */
	public static void requireDurableMode(RunStoreCapabilities store,
			Collection<RuntimeComponentDeclaration> components) {
		requireNonNull(store, "store cannot be null").requireDurableMode();
		requireRecoverableComponents(components);
	}

	/**
	 * Verify component recovery declarations without the store gate.
	 * @throws RecoveryCapabilityException if a component cannot be rebuilt from data or
	 * was not declared
	 */
	public static void requireRecoverableComponents(Collection<RuntimeComponentDeclaration> components) {
		Map<RuntimeComponentRole, RuntimeComponentDeclaration> declared = index(components);
		List<RuntimeComponentRole> missing = new ArrayList<>();
		for (RuntimeComponentRole role : RuntimeComponentRole.values()) {
			if (!declared.containsKey(role)) {
				missing.add(role);
			}
		}
		if (!missing.isEmpty()) {
			throw RecoveryCapabilityException.missingRoles(missing);
		}
		List<RuntimeComponentDeclaration> offending = new ArrayList<>();
		for (RuntimeComponentDeclaration declaration : declared.values()) {
			if (!declaration.recoverable()) {
				offending.add(declaration);
			}
		}
		if (!offending.isEmpty()) {
			throw RecoveryCapabilityException.notRecoverable(offending);
		}
	}

	private static Map<RuntimeComponentRole, RuntimeComponentDeclaration> index(
			Collection<RuntimeComponentDeclaration> components) {
		EnumMap<RuntimeComponentRole, RuntimeComponentDeclaration> declared = new EnumMap<>(RuntimeComponentRole.class);
		for (RuntimeComponentDeclaration declaration : requireNonNull(components, "components cannot be null")) {
			RuntimeComponentDeclaration component = requireNonNull(declaration, "components cannot contain null");
			if (declared.putIfAbsent(component.role(), component) != null) {
				throw new IllegalArgumentException("Role declared more than once: " + component.role().wireName());
			}
		}
		return declared;
	}

}
