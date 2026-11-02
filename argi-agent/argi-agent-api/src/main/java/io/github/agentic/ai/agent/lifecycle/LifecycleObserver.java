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

import java.util.Set;

/**
 * An extension that observes lifecycle events without changing them.
 *
 * <p>
 * The observer is the weakest extension kind and therefore the easiest to adopt. It
 * cannot rewrite a request, cannot short-circuit a stage and cannot veto anything.
 *
 * <p>
 * A failing observer must not break the run. The contract makes the failure explicit
 * instead of hiding it: the engine reports it as a
 * {@link LifecycleEvent.ExtensionFailed} event and continues.
 */
public interface LifecycleObserver extends LifecycleExtension {

	/**
	 * Observes one lifecycle event.
	 * @param event the event, never null
	 */
	void onEvent(LifecycleEvent event);

	/**
	 * The stages this observer is bound to.
	 * @return the bound stages, never null
	 */
	@Override
	Set<LifecycleStage> stages();

	@Override
	default LifecycleCapabilities capabilities() {
		return LifecycleCapabilities.synchronousOnly();
	}

}
