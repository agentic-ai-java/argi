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
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Recorded data cannot be read or executed by the current runtime because a version
 * does not match.
 *
 * <p>
 * Two distinct situations share this structured error: a definition envelope version
 * this runtime cannot read, and a recorded data codec this worker cannot execute.
 * Callers branch on {@link #code()}, {@link #subject()}, {@link #recorded()} and
 * {@link #supported()}.
 */
public final class CodecIncompatibilityException extends IllegalStateException {

	private final DefinitionFailureCode code;

	private final String subject;

	private final String recorded;

	private final List<String> supported;

	private CodecIncompatibilityException(DefinitionFailureCode code, String subject, String recorded,
			List<String> supported, String message) {
		super(message);
		this.code = code;
		this.subject = subject;
		this.recorded = recorded;
		this.supported = List.copyOf(supported);
	}

	/** The recorded definition envelope version is newer than this runtime can read. */
	public static CodecIncompatibilityException envelopeVersion(int recordedVersion, int maxSupportedVersion) {
		return new CodecIncompatibilityException(DefinitionFailureCode.ENVELOPE_VERSION_UNSUPPORTED,
				"definition envelope", Integer.toString(recordedVersion), List.of(Integer.toString(maxSupportedVersion)),
				"Definition envelope version " + recordedVersion + " is not supported; up to " + maxSupportedVersion
						+ " is readable");
	}

	/** The recorded data codec is not declared by the worker. */
	public static CodecIncompatibilityException codec(DefinitionReference codec,
			Set<DefinitionReference> supportedCodecs) {
		DefinitionReference target = requireNonNull(codec, "codec cannot be null");
		Set<DefinitionReference> supported = Set.copyOf(requireNonNull(supportedCodecs, "supportedCodecs cannot be null"));
		return new CodecIncompatibilityException(DefinitionFailureCode.CODEC_VERSION_UNSUPPORTED, target.display(),
				target.version(), supported.stream().map(DefinitionReference::display).sorted().toList(),
				"Data codec " + target.display() + " is not supported by this worker");
	}

	public DefinitionFailureCode code() {
		return this.code;
	}

	/** Either {@code "definition envelope"} or the recorded codec display form. */
	public String subject() {
		return this.subject;
	}

	/** The recorded version, or the recorded codec id for a codec failure. */
	public String recorded() {
		return this.recorded;
	}

	/** Supported versions, sorted, as data identifiers. */
	public List<String> supported() {
		return this.supported;
	}

}
