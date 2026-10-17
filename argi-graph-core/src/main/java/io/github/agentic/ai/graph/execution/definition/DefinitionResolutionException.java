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
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A recorded definition could not be resolved by an application-owned resolver.
 *
 * <p>
 * This is a structured failure: callers branch on {@link #code()}, the offending
 * {@link #reference()} and {@link #details()} instead of parsing the message. It is
 * thrown before any definition is handed to a worker, so no tool can have executed.
 */
public final class DefinitionResolutionException extends IllegalStateException {

	private final DefinitionFailureCode code;

	private final DefinitionReference reference;

	private final String expectedType;

	private final List<String> details;

	private DefinitionResolutionException(DefinitionFailureCode code, DefinitionReference reference, String message,
			String expectedType, List<String> details) {
		super(message);
		this.code = code;
		this.reference = reference;
		this.expectedType = expectedType;
		this.details = List.copyOf(details);
	}

	/** No definition is registered for the recorded kind, id and version. */
	public static DefinitionResolutionException notRegistered(DefinitionReference reference) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		return new DefinitionResolutionException(DefinitionFailureCode.NOT_REGISTERED, target,
				"No definition is registered for " + target.display(), null, List.of());
	}

	/** The registered definition is not assignable to the required type. */
	public static DefinitionResolutionException typeMismatch(DefinitionReference reference, Class<?> expectedType) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		Class<?> expected = requireNonNull(expectedType, "expectedType cannot be null");
		return new DefinitionResolutionException(DefinitionFailureCode.TYPE_MISMATCH, target,
				"Definition " + target.display() + " is not assignable to " + expected.getName(), expected.getName(),
				List.of());
	}

	/** The worker does not implement the recorded definition kind. */
	public static DefinitionResolutionException kindUnsupported(DefinitionReference reference,
			Set<DefinitionKind> supportedKinds) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		Set<DefinitionKind> supported = Set.copyOf(requireNonNull(supportedKinds, "supportedKinds cannot be null"));
		return new DefinitionResolutionException(DefinitionFailureCode.KIND_UNSUPPORTED, target,
				"Worker does not support definition kind " + target.kind().wireName(),
				null, supported.stream().map(DefinitionKind::wireName).sorted().toList());
	}

	/** The definition record does not describe the graph the run was admitted with. */
	public static DefinitionResolutionException recordMismatch(DefinitionReference expected,
			DefinitionReference recorded) {
		DefinitionReference target = requireNonNull(expected, "expected cannot be null");
		DefinitionReference actual = requireNonNull(recorded, "recorded cannot be null");
		return new DefinitionResolutionException(DefinitionFailureCode.RECORD_MISMATCH, target,
				"Run was admitted with " + target.display() + " but the definition record pins " + actual.display(),
				null, List.of(actual.display()));
	}

	public DefinitionFailureCode code() {
		return this.code;
	}

	/** The definition reference the failure is about. */
	public DefinitionReference reference() {
		return this.reference;
	}

	/** Required Java type for {@link DefinitionFailureCode#TYPE_MISMATCH}. */
	public Optional<String> expectedType() {
		return Optional.ofNullable(this.expectedType);
	}

	/** Additional structured context: supported kinds, or the conflicting recorded value. */
	public List<String> details() {
		return this.details;
	}

}
