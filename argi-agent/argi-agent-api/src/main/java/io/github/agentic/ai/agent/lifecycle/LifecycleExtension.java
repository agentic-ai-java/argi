/*
 * Copyright 2026 the original author or authors.
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
package io.github.agentic.ai.agent.lifecycle;

import java.util.Objects;
import java.util.Set;

/**
 * The root contract of a lifecycle extension.
 *
 * <p>
 * Every extension declares a stable identifier, the stages it binds to and the
 * execution modes it supports. The declaration is what an engine uses to reject an
 * extension it cannot honour: an engine that cannot run a bound stage asynchronously
 * must say so instead of quietly changing the semantics of the extension.
 */
public interface LifecycleExtension {

	/**
	 * The stable identifier of the extension.
	 * @return the extension identifier, never null
	 */
	String id();

	/**
	 * The stages this extension binds to.
	 * @return the bound stages, never null and never empty
	 */
	Set<LifecycleStage> stages();

	/**
	 * The execution modes this extension supports.
	 * @return the declared capabilities, never null
	 */
	LifecycleCapabilities capabilities();

	/**
	 * A single-stage binding helper for extensions that bind to exactly one stage.
	 * @param stage the bound stage, must not be null
	 * @return the set containing the stage
	 */
	static Set<LifecycleStage> boundTo(LifecycleStage stage) {
		return Set.of(Objects.requireNonNull(stage, "stage must not be null"));
	}

}
