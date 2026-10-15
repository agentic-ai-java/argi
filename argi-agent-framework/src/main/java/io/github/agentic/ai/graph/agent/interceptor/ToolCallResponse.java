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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.tool.ToolResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Response object for tool calls.
 */
public class ToolCallResponse {

	public final static String SUCCESS_STATUS = "SUCCESS";

	/** Status of an error response; see {@link ToolOutcome} for the full outcome vocabulary. */
	public static final String ERROR_STATUS = "error";

	/**
	 * Metadata key carrying the structured failure kind of an error response. Values are
	 * one of {@link #FAILURE_KIND_TIMEOUT}, {@link #FAILURE_KIND_CANCELLED},
	 * {@link #FAILURE_KIND_EXECUTION} or {@link #FAILURE_KIND_UNRESOLVED}.
	 */
	public static final String FAILURE_KIND_METADATA_KEY = "failureKind";

	/**
	 * Metadata key carrying the fully qualified class name of the exception that caused
	 * the failure, when one is available.
	 */
	public static final String EXCEPTION_TYPE_METADATA_KEY = "exceptionType";

	/** The tool did not finish within its timeout; its effect is unknown. */
	public static final String FAILURE_KIND_TIMEOUT = "timeout";

	/** The tool execution was cancelled or interrupted; its effect is unknown. */
	public static final String FAILURE_KIND_CANCELLED = "cancelled";

	/** The tool was invoked and failed with an exception or an error result. */
	public static final String FAILURE_KIND_EXECUTION = "execution";

	/** No tool with the requested name could be resolved; nothing was invoked. */
	public static final String FAILURE_KIND_UNRESOLVED = "unresolved";

	private final String result;

	private final List<ContentPart> contentParts;

	private final String toolName;

	private final String toolCallId;

	private final String status;

	private final Map<String, Object> metadata;

	public ToolCallResponse(String result, String toolName, String toolCallId) {
		this(result, toolName, toolCallId, null, null);
	}

	public ToolCallResponse(String result, String toolName, String toolCallId, String status,
			Map<String, Object> metadata) {
		this(result, result == null ? List.of() : List.of(ContentPart.text(result)), toolName, toolCallId, status,
				metadata);
	}

	private ToolCallResponse(String result, List<ContentPart> contentParts, String toolName, String toolCallId,
			String status, Map<String, Object> metadata) {
		this.result = result;
		this.contentParts = List.copyOf(contentParts);
		this.toolName = toolName;
		this.toolCallId = toolCallId;
		this.status = status;
		this.metadata = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();
	}

	/** Preserves complete neutral tool output without flattening content or metadata. */
	public static ToolCallResponse from(ToolCall call, ToolResult result) {
		ChatMessage message = result.toMessage(call);
		return new ToolCallResponse(message.text(), result.content(), call.name(), call.id(),
				result.error() ? ERROR_STATUS : SUCCESS_STATUS, result.metadata());
	}

	/**
	 * Same as {@link #from(ToolCall, ToolResult)}, but with an explicit outcome status
	 * (see {@link ToolOutcome}) instead of the two-value mapping of
	 * {@link ToolResult#error()}.
	 * @param call the tool call being finalized
	 * @param result the result produced by the tool
	 * @param status the outcome status to record
	 * @return a new ToolCallResponse carrying the given status
	 */
	public static ToolCallResponse from(ToolCall call, ToolResult result, String status) {
		ChatMessage message = result.toMessage(call);
		return new ToolCallResponse(message.text(), result.content(), call.name(), call.id(), status,
				result.metadata());
	}

	/**
	 * Creates a response without an explicit status. Kept for backward compatibility;
	 * new code should use {@link #success(String, String, String)} or one of the
	 * {@code error} factories so the outcome is machine-readable (see {@link ToolOutcome}).
	 * @param toolCallId the tool call ID
	 * @param toolName the tool name
	 * @param result the response content
	 * @return a new ToolCallResponse with no status set
	 */
	public static ToolCallResponse of(String toolCallId, String toolName, String result) {
		return new ToolCallResponse(result, toolName, toolCallId);
	}

    /**
     *  <p>
     *   Rewrite the status field of the source code to SUCCESS, and then make a judgment in the tool interceptor
     *  </p>
     * @param toolCallId toolCallId
     * @param toolName   toolName
     * @param result     response
     * @return
     */
    public static ToolCallResponse success(String toolCallId, String toolName, String result) {
        return new ToolCallResponse(result, toolName, toolCallId, SUCCESS_STATUS, null);
    }

	/**
	 * Creates an error response for a failed tool execution.
	 * @param toolCallId the tool call ID
	 * @param toolName the tool name
	 * @param errorMessage the error message
	 * @return a new error ToolCallResponse
	 */
	public static ToolCallResponse error(String toolCallId, String toolName, String errorMessage) {
		return new ToolCallResponse("Error: " + errorMessage, toolName, toolCallId, ERROR_STATUS,
				Map.of("error", true, "errorMessage", errorMessage));
	}

	/**
	 * Creates an error response for a failed tool execution from a Throwable. Preserves
	 * the exception class name when message is null.
	 * @param toolCallId the tool call ID
	 * @param toolName the tool name
	 * @param cause the exception that caused the failure
	 * @return a new error ToolCallResponse
	 */
	public static ToolCallResponse error(String toolCallId, String toolName, Throwable cause) {
		String errorMessage = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
		return error(toolCallId, toolName, errorMessage);
	}

	/**
	 * Creates an error response that carries additional metadata next to the standard
	 * {@code error} and {@code errorMessage} entries, for example the structured
	 * {@link #FAILURE_KIND_METADATA_KEY failure kind} and
	 * {@link #EXCEPTION_TYPE_METADATA_KEY exception type}.
	 * @param toolCallId the tool call ID
	 * @param toolName the tool name
	 * @param errorMessage the error message
	 * @param additionalMetadata extra metadata entries; may be null or empty
	 * @return a new error ToolCallResponse
	 */
	public static ToolCallResponse error(String toolCallId, String toolName, String errorMessage,
			Map<String, Object> additionalMetadata) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("error", true);
		metadata.put("errorMessage", errorMessage);
		if (additionalMetadata != null) {
			metadata.putAll(additionalMetadata);
		}
		return new ToolCallResponse("Error: " + errorMessage, toolName, toolCallId, ERROR_STATUS, metadata);
	}

	/**
	 * Creates an error response from a Throwable that carries additional metadata. The
	 * message is derived the same way as {@link #error(String, String, Throwable)}.
	 * @param toolCallId the tool call ID
	 * @param toolName the tool name
	 * @param cause the exception that caused the failure
	 * @param additionalMetadata extra metadata entries; may be null or empty
	 * @return a new error ToolCallResponse
	 */
	public static ToolCallResponse error(String toolCallId, String toolName, Throwable cause,
			Map<String, Object> additionalMetadata) {
		String errorMessage = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
		return error(toolCallId, toolName, errorMessage, additionalMetadata);
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getResult() {
		return result;
	}

	public String getToolName() {
		return toolName;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public String getStatus() {
		return status;
	}

	public Map<String, Object> getMetadata() {
		return Collections.unmodifiableMap(metadata);
	}

	/**
	 * Checks if this response represents a failure: either a generic error or a
	 * business-level failure reported by a registered {@link BusinessOutcomeMapper}.
	 * @return true if this is a failed response
	 */
	public boolean isError() {
		return ERROR_STATUS.equals(status) || ToolOutcome.BUSINESS_FAILED.equals(status);
	}

	/**
	 * Returns the structured failure kind recorded under
	 * {@link #FAILURE_KIND_METADATA_KEY}, if present.
	 * @return the failure kind, or empty when the response carries none
	 */
	public Optional<String> getFailureKind() {
		return metadataString(FAILURE_KIND_METADATA_KEY);
	}

	/**
	 * Returns the exception class name recorded under
	 * {@link #EXCEPTION_TYPE_METADATA_KEY}, if present.
	 * @return the exception type, or empty when the response carries none
	 */
	public Optional<String> getExceptionType() {
		return metadataString(EXCEPTION_TYPE_METADATA_KEY);
	}

	private Optional<String> metadataString(String key) {
		Object value = metadata.get(key);
		return value instanceof String s ? Optional.of(s) : Optional.empty();
	}

	/** Creates a neutral tool message, preserving failure and application metadata. */
	public ChatMessage toMessage() {
		return new ToolResult(contentParts, isError(), metadata).toMessage(new ToolCall(toolCallId, toolName, "{}"));
	}

	public static class Builder {

		private String content;

		private String toolName;

		private String toolCallId;

		private String status;

		private Map<String, Object> metadata;

		public Builder content(String content) {
			this.content = content;
			return this;
		}

		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

		public Builder toolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
			return this;
		}

		public Builder status(String status) {
			this.status = status;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public ToolCallResponse build() {
			return new ToolCallResponse(content, toolName, toolCallId, status, metadata);
		}

	}

}
