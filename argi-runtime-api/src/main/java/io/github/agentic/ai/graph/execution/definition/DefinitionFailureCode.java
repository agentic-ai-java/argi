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
 * Machine-readable reason for a definition resolution or codec compatibility failure.
 * Callers must branch on the code; the exception message is for humans only.
 */
public enum DefinitionFailureCode {

	/** No definition is registered for the recorded kind, id and version. */
	NOT_REGISTERED,

	/** The worker does not implement the recorded definition kind. */
	KIND_UNSUPPORTED,

	/** The registered definition is not of the type the caller requires. */
	TYPE_MISMATCH,

	/** The definition record does not match the graph the run was admitted with. */
	RECORD_MISMATCH,

	/** The recorded definition envelope version cannot be read by this runtime. */
	ENVELOPE_VERSION_UNSUPPORTED,

	/** The recorded data codec is not one this worker can execute. */
	CODEC_VERSION_UNSUPPORTED,

	/** A runtime component cannot be rebuilt from committed data, or is not declared. */
	COMPONENT_NOT_RECOVERABLE

}
