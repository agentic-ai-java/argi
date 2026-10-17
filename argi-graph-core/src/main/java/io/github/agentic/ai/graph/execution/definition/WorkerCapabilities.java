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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

/**
 * What a worker build can execute after a restart.
 *
 * <p>
 * The declaration is an implementer obligation, not a hint: a worker that advertises a
 * kind or a codec must be able to execute it, and the worker records the envelope version
 * it can read. Advertising a capability is verified by tests, exactly like
 * {@code RunStoreCapabilities} is.
 *
 * @param maxDefinitionSchemaVersion highest definition envelope version this worker reads
 * @param supportedKinds definition kinds this worker implements
 * @param supportedCodecs codec references this worker can execute
 */
public record WorkerCapabilities(int maxDefinitionSchemaVersion, Set<DefinitionKind> supportedKinds,
		Set<DefinitionReference> supportedCodecs) {

	public WorkerCapabilities {
		if (maxDefinitionSchemaVersion <= 0) {
			throw new IllegalArgumentException("maxDefinitionSchemaVersion must be positive");
		}
		EnumSet<DefinitionKind> kinds = EnumSet.noneOf(DefinitionKind.class);
		kinds.addAll(requireNonNull(supportedKinds, "supportedKinds cannot be null"));
		supportedKinds = Collections.unmodifiableSet(kinds);
		Set<DefinitionReference> codecs = new TreeSet<>(DefinitionReference.ORDER);
		for (DefinitionReference codec : requireNonNull(supportedCodecs, "supportedCodecs cannot be null")) {
			requireNonNull(codec, "supportedCodecs cannot contain null");
			if (codec.kind() != DefinitionKind.CODEC) {
				throw new IllegalArgumentException("supported codec must be a codec reference, was " + codec);
			}
			codecs.add(codec);
		}
		supportedCodecs = Collections.unmodifiableSet(codecs);
	}

	public boolean supportsKind(DefinitionKind kind) {
		return this.supportedKinds.contains(requireNonNull(kind, "kind cannot be null"));
	}

	public boolean supportsCodec(DefinitionReference codec) {
		DefinitionReference target = requireNonNull(codec, "codec cannot be null");
		return target.kind() == DefinitionKind.CODEC && this.supportedCodecs.contains(target);
	}

	/** Fail when the recorded envelope version is newer than this worker can read. */
	public void requireDefinitionSchemaVersion(int recordedSchemaVersion) {
		if (recordedSchemaVersion > this.maxDefinitionSchemaVersion) {
			throw CodecIncompatibilityException.envelopeVersion(recordedSchemaVersion,
					this.maxDefinitionSchemaVersion);
		}
	}

	/** Fail when this worker does not implement the recorded definition kind. */
	public void requireKind(DefinitionReference reference) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		if (!this.supportedKinds.contains(target.kind())) {
			throw DefinitionResolutionException.kindUnsupported(target, this.supportedKinds);
		}
	}

	/** Fail when this worker cannot execute the recorded data codec. */
	public void requireCodec(DefinitionReference reference) {
		DefinitionReference target = requireNonNull(reference, "reference cannot be null");
		if (target.kind() != DefinitionKind.CODEC) {
			throw new IllegalArgumentException("codec reference expected, was " + target);
		}
		if (!this.supportedCodecs.contains(target)) {
			throw CodecIncompatibilityException.codec(target, this.supportedCodecs);
		}
	}

}
