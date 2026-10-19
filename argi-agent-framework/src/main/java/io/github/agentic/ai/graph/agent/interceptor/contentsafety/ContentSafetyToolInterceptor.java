/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.interceptor.contentsafety;

import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyArgumentReview;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyDecision;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyGate;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyKeys;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyStage;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Checks tool arguments before dispatch and tool results, receipts, and return-direct
 * payloads before they are stored or returned. Register this interceptor inside result
 * eviction so a blocked or redacted payload is what later interceptors persist.
 */
public class ContentSafetyToolInterceptor extends ToolInterceptor {

	private final ContentSafetyGate gate;

	private final ContentSafetyArgumentReview argumentReview;

	public ContentSafetyToolInterceptor(ContentSafetyGate gate) {
		this(gate, null);
	}

	public ContentSafetyToolInterceptor(ContentSafetyGate gate, ContentSafetyArgumentReview argumentReview) {
		this.gate = gate;
		this.argumentReview = argumentReview;
	}

	@Override
	public String getName() {
		return "ContentSafetyTool";
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		ContentSafetyDecision arguments = gate.inspect(ContentSafetyStage.TOOL_ARGS, request.getArguments());
		if (arguments.blocksDispatch()) {
			return blocked(request, arguments);
		}
		ToolCallRequest forwarded = request;
		if (!arguments.output().equals(request.getArguments())) {
			if (argumentReview == null
					|| !argumentReview.accept(request.getToolName(), request.getArguments(), arguments.output())) {
				return blocked(request, rejectedTransform());
			}
			forwarded = ToolCallRequest.builder(request).arguments(arguments.output()).build();
		}
		ToolCallResponse response = handler.call(forwarded);
		ContentSafetyStage resultStage = resultStage(response);
		ContentSafetyDecision result = gate.inspect(resultStage, response.getResult());
		if (result.action().name().equals("ALLOW") && result.output().equals(response.getResult())) {
			return response;
		}
		Map<String, Object> metadata = new HashMap<>(response.getMetadata());
		metadata.putAll(result.status());
		if (result.blocksDispatch()) {
			return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), result.output(), metadata);
		}
		return new ToolCallResponse(result.output(), request.getToolName(), request.getToolCallId(),
				response.getStatus(), metadata);
	}

	private ContentSafetyStage resultStage(ToolCallResponse response) {
		if (Boolean.TRUE.equals(response.getMetadata().get(ContentSafetyKeys.CACHED_RECEIPT))) {
			return ContentSafetyStage.RECEIPT;
		}
		if (Boolean.TRUE.equals(response.getMetadata().get(ContentSafetyKeys.CHILD_RESULT))) {
			return ContentSafetyStage.CHILD_RESULT;
		}
		Object finishReason = response.getMetadata().get("finishReason");
		if ("returnDirect".equals(finishReason)) {
			return ContentSafetyStage.RETURN_DIRECT;
		}
		return ContentSafetyStage.TOOL_RESULT;
	}

	private static ContentSafetyDecision rejectedTransform() {
		return new ContentSafetyDecision(ContentSafetyStage.TOOL_ARGS,
				io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyAction.BLOCK,
				ContentSafetyGate.statusText(ContentSafetyStage.TOOL_ARGS,
						io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyAction.BLOCK,
						ContentSafetyKeys.REASON_TRANSFORM_REJECTED),
				-1, -1, Map.of(ContentSafetyKeys.STAGE, ContentSafetyStage.TOOL_ARGS.name(), ContentSafetyKeys.ACTION,
						"BLOCK", ContentSafetyKeys.REASON, ContentSafetyKeys.REASON_TRANSFORM_REJECTED));
	}

	private static ToolCallResponse blocked(ToolCallRequest request, ContentSafetyDecision decision) {
		return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), decision.output(),
				decision.status());
	}

}
