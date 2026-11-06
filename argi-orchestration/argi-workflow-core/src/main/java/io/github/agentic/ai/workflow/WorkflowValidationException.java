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

import io.github.agentic.ai.agent.AgentResult;
import java.util.List;

/**
 * A workflow input or output does not match the schema declared by the definition.
 * Carries one structured issue per violating location, so the caller can point at
 * the field and the error type instead of parsing a message.
 */
public class WorkflowValidationException extends WorkflowExecutionException {

	private final List<AgentResult.OutputValidationIssue> issues;

	public WorkflowValidationException(String message, List<AgentResult.OutputValidationIssue> issues) {
		super(message);
		this.issues = List.copyOf(issues);
	}

	public List<AgentResult.OutputValidationIssue> issues() {
		return issues;
	}

}
