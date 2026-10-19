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
package io.github.agentic.ai.graph.agent.hook.contentsafety;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.graph.agent.hook.pii.PIIDetectors;
import io.github.agentic.ai.graph.agent.hook.returndirect.ReturnDirectConstants;
import io.github.agentic.ai.graph.agent.interceptor.InterceptorChain;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.contentsafety.ContentSafetyStreamingInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.contentsafety.ContentSafetyToolInterceptor;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSafetyPipelineTest {

	private static final String SECRET = "SECRET-TOKEN";

	@Test
	void toolArgumentsAreCheckedWhenModelTextIsCleanAndBlockSkipsDispatch() {
		AtomicInteger dispatches = new AtomicInteger();
		ContentSafetyToolInterceptor interceptor = interceptor(ContentSafetyAction.BLOCK, ContentSafetyStage.TOOL_ARGS);
		ToolCallResponse response = interceptor.interceptToolCall(request("{\"q\":\"" + SECRET + "\"}"), handler(dispatches, "ok"));

		assertEquals(0, dispatches.get());
		assertTrue(response.isError());
		assertFalse(response.getResult().contains(SECRET));
		assertEquals(ContentSafetyStage.TOOL_ARGS.name(), response.getMetadata().get(ContentSafetyKeys.STAGE));
	}

	@Test
	void piiInToolArgumentsIsCheckedByTheSharedDetector() {
		AtomicInteger dispatches = new AtomicInteger();
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.TOOL_ARGS)
			.onMatch(ContentSafetyAction.BLOCK)
			.detector(new PiiContentDetector("email", PIIDetectors.emailDetector()))
			.build();
		ContentSafetyToolInterceptor interceptor = new ContentSafetyToolInterceptor(gate);

		interceptor.interceptToolCall(request("{\"email\":\"person@example.com\"}"), handler(dispatches, "clean answer"));

		assertEquals(0, dispatches.get());
	}

	@Test
	void toolResultReceiptChildAndReturnDirectShareTheConfiguredCheck() {
		ContentSafetyToolInterceptor interceptor = interceptor(ContentSafetyAction.REDACT, ContentSafetyStage.TOOL_RESULT,
				ContentSafetyStage.RECEIPT, ContentSafetyStage.CHILD_RESULT, ContentSafetyStage.RETURN_DIRECT);

		assertFalse(checked(interceptor, Map.of(), ContentSafetyStage.TOOL_RESULT).contains(SECRET));
		assertFalse(checked(interceptor, Map.of(ContentSafetyKeys.CACHED_RECEIPT, true), ContentSafetyStage.RECEIPT)
			.contains(SECRET));
		assertFalse(checked(interceptor, Map.of(ContentSafetyKeys.CHILD_RESULT, true), ContentSafetyStage.CHILD_RESULT)
			.contains(SECRET));
		assertFalse(checked(interceptor, Map.of(ReturnDirectConstants.FINISH_REASON_METADATA_KEY,
				ReturnDirectConstants.FINISH_REASON), ContentSafetyStage.RETURN_DIRECT).contains(SECRET));
	}

	@Test
	void redactedToolArgumentsAreRejectedUnlessRevalidated() {
		AtomicInteger dispatches = new AtomicInteger();
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.TOOL_ARGS)
			.onMatch(ContentSafetyAction.REDACT)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build();
		ContentSafetyToolInterceptor interceptor = new ContentSafetyToolInterceptor(gate);
		ToolCallResponse response = interceptor.interceptToolCall(request("{\"q\":\"" + SECRET + "\"}"),
				handler(dispatches, "ok"));

		assertEquals(0, dispatches.get());
		assertEquals(ContentSafetyKeys.REASON_TRANSFORM_REJECTED, response.getMetadata().get(ContentSafetyKeys.REASON));
	}

	@Test
	void requireReviewDoesNotDispatchAndMarksHitlMetadata() {
		AtomicInteger dispatches = new AtomicInteger();
		ContentSafetyToolInterceptor interceptor = interceptor(ContentSafetyAction.REQUIRE_REVIEW,
				ContentSafetyStage.TOOL_ARGS);
		ToolCallResponse response = interceptor.interceptToolCall(request(SECRET), handler(dispatches, "ok"));

		assertEquals(0, dispatches.get());
		assertEquals(Boolean.TRUE, response.getMetadata().get(ContentSafetyKeys.REVIEW));
		assertFalse(response.getResult().contains(SECRET));
	}

	@Test
	void detectorTimeoutBlocksWithStructuredStatus() {
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.FINAL_OUTPUT)
			.timeoutMillis(30)
			.onFailure(ContentSafetyFailurePolicy.BLOCK)
			.detector(new ContentDetector() {
				@Override
				public String name() {
					return "slow";
				}

				@Override
				public List<ContentSpan> detect(String text) {
					try {
						Thread.sleep(200);
					}
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
					return List.of();
				}
			})
			.build();

		ContentSafetyDecision decision = gate.inspect(ContentSafetyStage.FINAL_OUTPUT, SECRET);

		assertEquals(ContentSafetyAction.BLOCK, decision.action());
		assertEquals(ContentSafetyKeys.REASON_TIMEOUT, decision.status().get(ContentSafetyKeys.REASON));
		assertFalse(decision.output().contains(SECRET));
	}

	@Test
	void auditRecordDoesNotContainThePayload() {
		List<String> records = new ArrayList<>();
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.REQUEST_INGRESS)
			.onMatch(ContentSafetyAction.BLOCK)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.audit((stage, action, reason, detector) -> records
				.add(stage + " " + action + " " + reason + " " + detector))
			.build();

		gate.inspect(ContentSafetyStage.REQUEST_INGRESS, "payload " + SECRET);

		assertEquals(1, records.size());
		assertFalse(records.get(0).contains(SECRET));
	}

	@Test
	void strictStreamDoesNotEmitASecretSplitAcrossChunks() {
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.MODEL_EGRESS)
			.onMatch(ContentSafetyAction.BLOCK)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build();
		Flux<ChatEvent> events = Flux.just(ChatEvent.textDelta("pay "), ChatEvent.textDelta("SECRET-"),
				ChatEvent.textDelta("TOKEN"), ChatEvent.response(ModelResponse.of(ChatMessage.assistant("pay " + SECRET)).getChatResponse()));
		List<ChatEvent> emitted = JdkFlowAdapter
			.flowPublisherToFlux(InterceptorChain.applyStreamingInterceptors(List.of(new ContentSafetyStreamingInterceptor(gate)),
					JdkFlowAdapter.publisherToFlowPublisher(events), request()))
			.collectList()
			.block();

		String text = emitted.stream()
			.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
			.map(ChatEvent::text)
			.reduce("", String::concat);
		String response = emitted.stream()
			.filter(event -> event.type() == ChatEvent.Type.RESPONSE)
			.map(event -> event.response().text())
			.findFirst()
			.orElse("");
		assertFalse(text.contains(SECRET));
		assertFalse(response.contains(SECRET));
		assertTrue(text.contains("pay "));
	}

	@Test
	void strictStreamReleasesSafeTextAfterABlockedSecret() {
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.MODEL_EGRESS)
			.onMatch(ContentSafetyAction.BLOCK)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build();
		String full = "hello " + SECRET + " tail";
		Flux<ChatEvent> events = Flux.just(ChatEvent.textDelta("hello "), ChatEvent.textDelta("SECRET-"),
				ChatEvent.textDelta("TOKEN"), ChatEvent.textDelta(" tail"),
				ChatEvent.response(ModelResponse.of(ChatMessage.assistant(full)).getChatResponse()));
		List<ChatEvent> emitted = JdkFlowAdapter
			.flowPublisherToFlux(InterceptorChain.applyStreamingInterceptors(List.of(new ContentSafetyStreamingInterceptor(gate)),
					JdkFlowAdapter.publisherToFlowPublisher(events), request()))
			.collectList()
			.block();

		String text = emitted.stream()
			.filter(event -> event.type() == ChatEvent.Type.TEXT_DELTA)
			.map(ChatEvent::text)
			.reduce("", String::concat);
		assertFalse(text.contains(SECRET));
		assertTrue(text.contains("hello "));
		assertTrue(text.contains(" tail"));
	}

	@Test
	void detectorFailureBlocksWithStructuredStatus() {
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.FINAL_OUTPUT)
			.onFailure(ContentSafetyFailurePolicy.BLOCK)
			.detector(new ContentDetector() {
				@Override
				public String name() {
					return "broken";
				}

				@Override
				public List<ContentSpan> detect(String text) {
					throw new IllegalStateException("detector failed");
				}
			})
			.build();

		ContentSafetyDecision decision = gate.inspect(ContentSafetyStage.FINAL_OUTPUT, SECRET);

		assertEquals(ContentSafetyAction.BLOCK, decision.action());
		assertEquals(ContentSafetyKeys.REASON_DETECTOR_FAILURE, decision.status().get(ContentSafetyKeys.REASON));
		assertFalse(decision.output().contains(SECRET));
	}

	@Test
	void reviewedArgumentTransformIsDispatched() {
		AtomicInteger dispatches = new AtomicInteger();
		ContentSafetyGate gate = ContentSafetyGate.builder()
			.stage(ContentSafetyStage.TOOL_ARGS)
			.onMatch(ContentSafetyAction.REDACT)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build();
		ContentSafetyToolInterceptor interceptor = new ContentSafetyToolInterceptor(gate,
				(toolName, original, transformed) -> transformed.contains("[REDACTED]") && !transformed.contains(SECRET));
		ToolCallResponse response = interceptor.interceptToolCall(request("{\"q\":\"" + SECRET + "\"}"),
				handler(dispatches, "ok"));

		assertEquals(1, dispatches.get());
		assertFalse(response.isError());
		assertEquals("ok", response.getResult());
	}

	@Test
	void innerCheckRunsBeforeAnOuterPersistingInterceptor() {
		AtomicInteger dispatches = new AtomicInteger();
		List<String> persisted = new ArrayList<>();
		ToolInterceptor persister = new ToolInterceptor() {
			@Override
			public String getName() {
				return "persister";
			}

			@Override
			public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
				ToolCallResponse response = handler.call(request);
				persisted.add(response.getResult());
				return response;
			}
		};
		ContentSafetyToolInterceptor safety = interceptor(ContentSafetyAction.REDACT, ContentSafetyStage.TOOL_RESULT);
		var chained = InterceptorChain.chainToolInterceptors(List.of(persister, safety), handler(dispatches, "see " + SECRET));

		chained.call(request("{}"));

		assertEquals(1, dispatches.get());
		assertEquals(1, persisted.size());
		assertFalse(persisted.get(0).contains(SECRET));
	}

	private static String checked(ContentSafetyToolInterceptor interceptor, Map<String, Object> metadata,
			ContentSafetyStage expected) {
		AtomicInteger dispatches = new AtomicInteger();
		ToolCallHandler handler = request -> new ToolCallResponse("see " + SECRET, request.getToolName(),
				request.getToolCallId(), ToolCallResponse.SUCCESS_STATUS, metadata);
		ToolCallResponse response = interceptor.interceptToolCall(request("{}"), handler);
		assertTrue(dispatches.get() >= 0);
		assertEquals(expected.name(), response.getMetadata().get(ContentSafetyKeys.STAGE));
		return response.getResult();
	}

	private static ContentSafetyToolInterceptor interceptor(ContentSafetyAction action, ContentSafetyStage... stages) {
		return new ContentSafetyToolInterceptor(ContentSafetyGate.builder()
			.stages(stages)
			.onMatch(action)
			.detector(new LiteralContentDetector("secret", List.of(SECRET)))
			.build());
	}

	private static ToolCallRequest request(String arguments) {
		return ToolCallRequest.builder().toolName("lookup").toolCallId("call-1").arguments(arguments).build();
	}

	private static ToolCallHandler handler(AtomicInteger dispatches, String result) {
		return request -> {
			dispatches.incrementAndGet();
			return ToolCallResponse.success(request.getToolCallId(), request.getToolName(), result);
		};
	}

	private static ModelRequest request() {
		return ModelRequest.builder()
			.messages(new ArrayList<>(List.of(ChatMessage.user("hi"))))
			.options(GenerationOptions.defaults())
			.build();
	}

}
