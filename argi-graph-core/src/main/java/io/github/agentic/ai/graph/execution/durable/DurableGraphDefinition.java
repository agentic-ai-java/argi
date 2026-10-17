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
package io.github.agentic.ai.graph.execution.durable;

import io.github.agentic.ai.graph.CompiledGraph;
import io.github.agentic.ai.graph.checkpoint.LeasedCheckpointSaver;
import io.github.agentic.ai.graph.execution.definition.ResolvedRunDefinitions;
import io.github.agentic.ai.graph.execution.definition.RunDefinitionAdmission;
import io.github.agentic.ai.graph.execution.definition.RuntimeComponentDeclaration;

import java.util.List;
import java.util.Objects;

/**
 * Application-owned, version-registered graph factory. The replay declaration covers
 * ALL reachable nodes, tools, routing functions and lifecycle callbacks, including
 * dynamically selected tools in the pinned catalog. It is a semantic promise, not
 * inferred from Java types or from a component's data-only recovery declaration.
 * Factories must be side-effect-free and rebuild clients from application configuration,
 * never from persisted credentials or an earlier JVM instance.
 */
public record DurableGraphDefinition(Factory factory, ReplaySafety replaySafety,
		List<RuntimeComponentDeclaration> components) {

	public DurableGraphDefinition {
		Objects.requireNonNull(factory, "factory cannot be null");
		Objects.requireNonNull(replaySafety, "replaySafety must be explicitly declared");
		components = List.copyOf(components);
	}

	void requireRecoverable() {
		replaySafety.requireSafe();
		RunDefinitionAdmission.requireRecoverableComponents(components);
	}

	@FunctionalInterface
	public interface Factory {
		/**
		 * Compile the pinned graph using the supplied saver, with releaseThread=false.
		 * The saver owns the entire run boundary; independent child checkpoint stores
		 * and instance-bound background work are not supported by this execution path.
		 */
		CompiledGraph compile(ResolvedRunDefinitions definitions, LeasedCheckpointSaver saver) throws Exception;
	}
}
