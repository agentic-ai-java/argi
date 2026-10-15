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
package io.github.agentic.ai.api.chat;

import java.util.HashMap;
import java.util.Map;

/**
 * Assembles model interceptor context from graph state and runtime metadata.
 *
 * <p>
 * User preferences and retrieval facts may live in state data, but
 * {@link ContextAuthorityKeys protected authority keys} are accepted only from trusted
 * runtime metadata so ordinary payloads cannot forge authorization or secret config.
 */
public final class ModelContextMerger {

	private ModelContextMerger() {
	}

	/**
	 * Merges state data and runtime metadata into a model context map.
	 * @param stateData graph state data (including {@code messages} for interceptor
	 * compatibility); protected authority keys in this map are ignored
	 * @param runtimeMetadata trusted runtime metadata (for example from
	 * {@code RunnableConfig}); may set protected authority keys and overwrite ordinary
	 * state keys
	 * @return a mutable context map suitable for {@code ModelRequest}
	 */
	public static Map<String, Object> merge(Map<String, Object> stateData, Map<String, Object> runtimeMetadata) {
		Map<String, Object> context = new HashMap<>();
		if (stateData != null) {
			for (Map.Entry<String, Object> entry : stateData.entrySet()) {
				String key = entry.getKey();
				// Keep ordinary state (including messages) for interceptor compatibility.
				// Authority keys must come from trusted runtime metadata only.
				if (ContextAuthorityKeys.isProtected(key)) {
					continue;
				}
				context.put(key, entry.getValue());
			}
		}
		if (runtimeMetadata != null) {
			for (Map.Entry<String, Object> entry : runtimeMetadata.entrySet()) {
				if (entry.getKey() != null) {
					context.put(entry.getKey(), entry.getValue());
				}
			}
		}
		return context;
	}

}
