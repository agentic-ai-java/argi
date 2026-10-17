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
 * Whether a runtime component can be rebuilt from committed data in a fresh JVM.
 */
public enum RecoveryCapability {

	/**
	 * Definition and state are committed as versioned data and re-resolved at the
	 * application boundary, so a new JVM rebuilds the component without the admitting
	 * instance.
	 */
	DATA_ONLY,

	/**
	 * The component depends on the admitting JVM instance: an in-memory cache, a callback,
	 * a Future or an equivalent handle. Such a component must not be used in durable mode.
	 */
	INSTANCE_BOUND,

	/** The application does not use this component role. */
	NOT_APPLICABLE

}
