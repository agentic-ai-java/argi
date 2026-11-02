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

import java.util.Optional;

/**
 * An extension that may rewrite a stage in a restricted way.
 *
 * <p>
 * The transformer receives the event of its bound stage and returns either a rewritten
 * event or {@link Optional#empty()} to keep the original. It cannot short-circuit the
 * stage: refusing work is an admission decision and belongs to the
 * {@link AdmissionGate}.
 *
 * <p>
 * The rewrite is restricted to the fields the event type declares as mutable.
 * {@link LifecycleMutationGuard} decides whether a rewrite stays inside that boundary;
 * a rewrite that changes the identity, the tool name or the model turn identity is
 * rejected.
 */
public interface LifecycleTransformer extends LifecycleExtension {

	/**
	 * The stage this transformer is bound to.
	 * @return the bound stage, never null
	 */
	LifecycleStage stage();

	/**
	 * Rewrites the event of the bound stage.
	 * @param event the event to rewrite, never null
	 * @return the rewritten event, or empty to keep the original
	 */
	Optional<LifecycleEvent> transform(LifecycleEvent event);

	@Override
	default java.util.Set<LifecycleStage> stages() {
		return LifecycleExtension.boundTo(stage());
	}

	@Override
	default LifecycleCapabilities capabilities() {
		return LifecycleCapabilities.synchronousOnly();
	}

}
