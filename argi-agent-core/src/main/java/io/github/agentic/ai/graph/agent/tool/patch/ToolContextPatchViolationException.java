/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.tool.patch;

/**
 * Thrown at the merge boundary when a tool context patch violates the configured
 * {@link ToolContextPatchPolicy} with
 * {@link ToolContextPatchPolicy.ViolationPolicy#ABORT_RUN}.
 *
 * <p>
 * The failing run is aborted before the violating patch can reach the next turn's state;
 * the carried failure identifies the offending key and error code without patch values.
 * </p>
 */
public class ToolContextPatchViolationException extends RuntimeException {

	private final ToolContextPatchValidator.PatchValidationFailure failure;

	/**
	 * Creates the exception from the validation failure that triggered it.
	 * @param failure the validation failure describing the violating patch
	 */
	public ToolContextPatchViolationException(ToolContextPatchValidator.PatchValidationFailure failure) {
		super(failure.message());
		this.failure = failure;
	}

	/**
	 * The validation failure that triggered the abort.
	 * @return the failure with error code and offending key
	 */
	public ToolContextPatchValidator.PatchValidationFailure failure() {
		return failure;
	}

}
