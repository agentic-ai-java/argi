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

/**
 * An extension that decides whether a stage may run.
 *
 * <p>
 * The gate is the only extension kind that may refuse work. A denial short-circuits
 * the stage with a reason; an approval request is handed to the approval channel of
 * the engine. An engine without an approval channel must degrade an approval request
 * to a denial instead of letting the stage through.
 *
 * <p>
 * The gate does not rewrite anything. A gate that wants to change a request combines
 * with a {@link LifecycleTransformer}; a gate that wants to wrap the stage combines
 * with an {@link AroundInterceptor}.
 */
public interface AdmissionGate extends LifecycleExtension {

	/**
	 * The stage this gate is bound to.
	 * @return the bound stage, never null
	 */
	LifecycleStage stage();

	/**
	 * Decides whether the stage may run.
	 * @param event the event of the bound stage, never null
	 * @return the decision, never null
	 */
	GateDecision admit(LifecycleEvent event);

	@Override
	default java.util.Set<LifecycleStage> stages() {
		return LifecycleExtension.boundTo(stage());
	}

	@Override
	default LifecycleCapabilities capabilities() {
		return LifecycleCapabilities.synchronousOnly();
	}

}
