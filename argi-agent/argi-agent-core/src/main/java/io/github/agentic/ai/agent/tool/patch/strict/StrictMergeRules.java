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
package io.github.agentic.ai.agent.tool.patch.strict;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Strict-mode merge rules for parallel tool patches.
 *
 * <p>
 * The legacy parallel merge silently resolves a key written by several tools without a
 * declared strategy as replace-last-in-tool-order. Strict mode refuses to guess: a key
 * with multiple writers must carry an explicit merge strategy, otherwise the conflict is
 * reported as a validation failure with the same shape as patch violations. Keys written
 * by a single tool keep the replace default — there is nothing to resolve.
 * </p>
 *
 * <p>
 * The check is engine-neutral on purpose: callers pass the set of keys that carry a
 * strategy, whatever the strategy vocabulary of the hosting runtime, so this class stays
 * free of runtime types.
 * </p>
 */
public final class StrictMergeRules {

	/** Failure code: several tools wrote a key that has no declared merge strategy. */
	public static final String UNDECLARED_CONFLICT = "UNDECLARED_CONFLICT";

	private StrictMergeRules() {
	}

	/**
	 * Finds the first key (in sorted order, for deterministic reporting) that several
	 * admitted patches write without a declared merge strategy.
	 * @param admittedPatches the patches that passed admission, in tool-call order;
	 * null or empty patches are skipped
	 * @param strategyKeys the state keys that carry a declared merge strategy, or
	 * {@code null} for none
	 * @return the first undeclared conflict, or empty when every multi-writer key has a
	 * strategy
	 */
	public static Optional<ConflictFailure> checkUndeclaredConflicts(List<Map<String, Object>> admittedPatches,
			Set<String> strategyKeys) {
		Map<String, Integer> writersByKey = new TreeMap<>();
		for (Map<String, Object> patch : admittedPatches) {
			if (patch == null) {
				continue;
			}
			for (String key : patch.keySet()) {
				writersByKey.merge(key, 1, Integer::sum);
			}
		}
		Set<String> strategies = strategyKeys != null ? strategyKeys : Set.of();
		for (Map.Entry<String, Integer> entry : writersByKey.entrySet()) {
			String key = entry.getKey();
			if (entry.getValue() > 1 && !strategies.contains(key)) {
				return Optional.of(new ConflictFailure(UNDECLARED_CONFLICT, key, "State key '" + key
						+ "' is written by " + entry.getValue() + " tools without a declared merge strategy"));
			}
		}
		return Optional.empty();
	}

	/**
	 * A merge-rule failure with a machine-readable error code and the offending key. The
	 * message never contains patch values.
	 * @param errorCode machine-readable error code
	 * @param key the conflicting state key
	 * @param message human-readable explanation without patch values
	 */
	public record ConflictFailure(String errorCode, String key, String message) {
	}

}
