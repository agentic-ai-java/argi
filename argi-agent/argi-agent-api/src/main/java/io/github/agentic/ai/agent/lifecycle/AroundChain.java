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
 * The continuation an around interceptor wraps.
 *
 * <p>
 * The chain is the part of the stage that actually performs the work. An interceptor
 * may call it zero times to short-circuit the stage, once for the normal path, or more
 * than once when the stage is retried.
 */
@FunctionalInterface
public interface AroundChain {

	/**
	 * Continues the stage.
	 * @param event the event handed to the stage, never null
	 * @return the event produced by the stage, never null
	 */
	LifecycleEvent proceed(LifecycleEvent event);

}
