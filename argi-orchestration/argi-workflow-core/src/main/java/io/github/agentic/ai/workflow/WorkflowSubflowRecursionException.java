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
package io.github.agentic.ai.workflow;

import java.util.List;

/**
 * A subflow reference crosses the recursion boundary: either the reference closes a
 * cycle on the current call chain, or the chain grew past the configured depth limit.
 * Carries the resolved reference chain ({@code id@version} entries, caller first) so
 * the error locates the definitions and versions involved instead of exhausting the
 * JVM stack.
 */
public class WorkflowSubflowRecursionException extends WorkflowExecutionException {

	private final List<String> chain;

	public WorkflowSubflowRecursionException(String message, List<String> chain) {
		super(message);
		this.chain = List.copyOf(chain);
	}

	public List<String> chain() {
		return chain;
	}

}
