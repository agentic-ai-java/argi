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
package io.github.agentic.ai.graph.agent.tool.patch;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Validates a tool's context patch against the framework reserved namespace and the
 * tool's declared {@link ToolContextPatchSpec}.
 *
 * <p>
 * Reserved keys are the {@code messages} protocol key and every key under the
 * {@code argi.} prefix (tool-binding snapshots, action decisions, and the lease/tenant/
 * policy keys the durable-execution roadmap adds under the same prefix). A tool patch
 * writing them could overwrite protocol output or framework bookkeeping, so they are
 * rejected outright whenever a {@link ToolContextPatchPolicy} is enabled.
 * </p>
 *
 * <p>
 * Deliberately a small, closed-rule validator in the spirit of
 * {@code ToolArgumentValidator}: keys are visited in sorted order so the reported
 * failure is deterministic, and failure messages identify the offending key and error
 * code but never include patch values, which may hold business secrets.
 * </p>
 */
public final class ToolContextPatchValidator {

	/** Failure code: the patch wrote a framework-reserved state key. */
	public static final String RESERVED_KEY = "RESERVED_KEY";

	/** Failure code: the patch wrote a key outside the tool's declared writable set. */
	public static final String KEY_NOT_DECLARED = "KEY_NOT_DECLARED";

	/** Failure code: the patch value is not assignable to the key's declared type. */
	public static final String TYPE_MISMATCH = "TYPE_MISMATCH";

	/** The single state key carrying the agent chat protocol. */
	static final String MESSAGES_KEY = "messages";

	/** Prefix of every framework-owned state key. */
	static final String RESERVED_PREFIX = "argi.";

	private ToolContextPatchValidator() {
	}

	/**
	 * Whether the given state key is reserved for the framework and can never be written
	 * by a tool patch.
	 * @param key the state key to test
	 * @return {@code true} for {@code messages} and any {@code argi.*} key
	 */
	public static boolean isReservedKey(String key) {
		return MESSAGES_KEY.equals(key) || (key != null && key.startsWith(RESERVED_PREFIX));
	}

	/**
	 * Validate a tool's context patch.
	 * @param spec the tool's declared spec, or {@code null} when the tool declared none
	 * @param toolName the tool whose patch is being validated, for failure reporting
	 * @param updates the patch the tool produced
	 * @return the first failure in sorted key order, or empty when the patch is
	 * acceptable
	 */
	public static Optional<PatchValidationFailure> validate(ToolContextPatchSpec spec, String toolName,
			Map<String, Object> updates) {
		if (updates == null || updates.isEmpty()) {
			return Optional.empty();
		}
		// Sorted iteration keeps the reported failure independent of map order.
		for (Map.Entry<String, Object> entry : new TreeMap<>(updates).entrySet()) {
			String key = entry.getKey();
			if (isReservedKey(key)) {
				return Optional.of(new PatchValidationFailure(RESERVED_KEY, key,
						"Tool '" + toolName + "' may not write reserved state key '" + key + "'"));
			}
			if (spec != null && !spec.isWritable(key)) {
				return Optional.of(new PatchValidationFailure(KEY_NOT_DECLARED, key,
						"Tool '" + toolName + "' did not declare state key '" + key + "' as writable"));
			}
			if (spec != null) {
				Class<?> declared = spec.declaredType(key).orElse(null);
				Object value = entry.getValue();
				if (declared != null && value != null && !declared.isInstance(value)) {
					return Optional.of(new PatchValidationFailure(TYPE_MISMATCH, key, "Tool '" + toolName
							+ "' wrote a value not assignable to " + declared.getSimpleName() + " on key '" + key + "'"));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * A patch validation failure with a machine-readable error code and the offending
	 * key. The message never contains patch values.
	 * @param errorCode machine-readable error code
	 * @param key the offending state key
	 * @param message human-readable explanation without patch values
	 */
	public record PatchValidationFailure(String errorCode, String key, String message) {
	}

}
