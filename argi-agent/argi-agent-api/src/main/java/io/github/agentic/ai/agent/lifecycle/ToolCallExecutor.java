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
 * Executes a tool call in the neutral lifecycle contract.
 *
 * <p>
 * The executor is the continuation an around interceptor wraps. It performs the actual
 * tool execution and returns its outcome.
 */
@FunctionalInterface
public interface ToolCallExecutor {

	/**
	 * Executes the tool call.
	 * @param invocation the tool call to execute, never null
	 * @return the outcome of the call, never null
	 */
	ToolCallOutcome execute(ToolCallInvocation invocation);

}
