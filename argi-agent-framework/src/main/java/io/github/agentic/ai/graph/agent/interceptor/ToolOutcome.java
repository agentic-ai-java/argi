/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.interceptor;

/**
 * Canonical outcome vocabulary for {@link ToolCallResponse#getStatus()}.
 *
 * <p>
 * The status field is historically a free-form {@link String}; this class constrains the
 * values that new code may produce. Legacy values keep their existing string form
 * ({@link #SUCCESS} equals {@link ToolCallResponse#SUCCESS_STATUS}, {@link #ERROR} equals
 * the value used by the {@code error} factories) so that existing consumers comparing
 * against {@link ToolCallResponse#SUCCESS_STATUS} or calling
 * {@link ToolCallResponse#isError()} are unaffected.
 * </p>
 *
 * <p>
 * Fine-grained failure detail is carried in the response metadata (see
 * {@link ToolCallResponse#FAILURE_KIND_METADATA_KEY failureKind},
 * {@link ToolCallResponse#EXCEPTION_TYPE_METADATA_KEY exceptionType} and the validation
 * {@code errorCode}/{@code fieldPath} entries). The reserved values below
 * ({@link #VALIDATION_DENIED}, {@link #AUTH_DENIED}, {@link #BUSINESS_FAILED},
 * {@link #TRANSIENT}, {@link #TIMEOUT}, {@link #CANCELLED}, {@link #UNCERTAIN}) are
 * declared here so the vocabulary is defined once; wiring individual producers to them,
 * business-failure mapping and a typed outcome field on the response are left to
 * follow-up changes.
 * </p>
 */
public final class ToolOutcome {

	/** The tool call completed and returned a result. */
	public static final String SUCCESS = ToolCallResponse.SUCCESS_STATUS;

	/** The tool call failed; the generic failure status used by all error paths. */
	public static final String ERROR = "error";

	/** The tool call was rejected before execution (e.g. argument validation failed). */
	public static final String VALIDATION_DENIED = "validation-denied";

	/** The caller is not authorized to invoke the tool. */
	public static final String AUTH_DENIED = "auth-denied";

	/** The tool ran and reported a business-level failure. */
	public static final String BUSINESS_FAILED = "business-failed";

	/** The failure is transient and may succeed on retry. */
	public static final String TRANSIENT = "transient";

	/** The tool did not finish within its deadline; its effect is unknown. */
	public static final String TIMEOUT = "timeout";

	/** The tool execution was cancelled or interrupted. */
	public static final String CANCELLED = "cancelled";

	/** The effect of the tool call could not be determined. */
	public static final String UNCERTAIN = "uncertain";

	private ToolOutcome() {
	}

}
