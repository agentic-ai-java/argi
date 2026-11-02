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
 * An extension that wraps a stage and can rewrite, short-circuit or repeat it.
 *
 * <p>
 * The around interceptor is the strongest extension kind: it may rewrite the event it
 * receives, may return a result without calling the chain at all, and may call the
 * chain more than once to implement a retry or a fallback.
 *
 * <p>
 * It may not change the trusted identity of the run. The contract does not expose a
 * way to rewrite the trusted execution context, and an interceptor that needs a
 * stronger identity must ask the run for a new one instead of editing the current one.
 *
 * <p>
 * A failing interceptor aborts the stage. The failure is explicit: the engine reports
 * it as a {@link LifecycleEvent.ExtensionFailed} event.
 */
public interface AroundInterceptor extends LifecycleExtension {

	/**
	 * The stage this interceptor is bound to.
	 * @return the bound stage, never null
	 */
	LifecycleStage stage();

	/**
	 * Wraps the stage.
	 * @param event the event of the bound stage, never null
	 * @param chain the continuation of the stage, never null
	 * @return the event that leaves the stage, never null
	 */
	LifecycleEvent intercept(LifecycleEvent event, AroundChain chain);

	@Override
	default java.util.Set<LifecycleStage> stages() {
		return LifecycleExtension.boundTo(stage());
	}

	@Override
	default LifecycleCapabilities capabilities() {
		return LifecycleCapabilities.synchronousOnly();
	}

}
