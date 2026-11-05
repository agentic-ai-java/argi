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

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowToolResultTest {

	private static final WorkflowStep.RetryPolicy RETRY = new WorkflowStep.RetryPolicy(3, Duration.ZERO);

	@Test
	void keepsPlainTextCompatible() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentOutcome outcome = invoke(ToolResult.text("plain"), calls);
		assertEquals("plain", outcome.outputs().get("source"));
		assertEquals(1, calls.get());
	}

	@Test
	void preservesEmptySuccessAndMetadata() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentOutcome outcome = invoke(new ToolResult(List.of(), false, Map.of("receipt", "ok")), calls);
		assertEquals(Map.of("content", List.of(), "error", false, "metadata", Map.of("receipt", "ok")),
				outcome.outputs().get("source"));
		assertEquals(1, calls.get());
	}

	@Test
	void representsEmptySuccessWithoutMetadata() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		assertEquals(Map.of("content", List.of(), "error", false, "metadata", Map.of()),
				invoke(new ToolResult(List.of(), false, Map.of()), calls).outputs().get("source"));
		assertEquals(1, calls.get());
	}

	@Test
	void preservesMultipleTextPartsAndMapsThemIntoFollowingAgent() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		ToolResult result = new ToolResult(List.of(ContentPart.text("first"), ContentPart.text("second")), false,
				Map.of());
		AgentOutcome outcome = invoke(result, calls,
				Map.of("second", new WorkflowValue.StepOutput("source", "content.1.text"), "first",
						new WorkflowValue.State("source.content.0.text")));
		Map<?, ?> output = assertInstanceOf(Map.class, outcome.outputs().get("source"));
		assertEquals(2, assertInstanceOf(List.class, output.get("content")).size());
		assertEquals(Map.of("first", "first", "second", "second"), outcome.outputs().get("consume"));
		assertEquals(1, calls.get());
	}

	@Test
	void preservesJsonAsTypedContentWithoutReinterpretingItsText() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		String json = "{\"items\":[1,null]}";
		AgentOutcome outcome = invoke(new ToolResult(List.of(ContentPart.json(json)), false, Map.of()), calls,
				Map.of("json", new WorkflowValue.StepOutput("source", "content.0.text"), "type",
						new WorkflowValue.StepOutput("source", "content.0.type"), "mime",
						new WorkflowValue.StepOutput("source", "content.0.mimeType")));
		assertEquals(Map.of("json", json, "type", "JSON", "mime", "application/json"),
				outcome.outputs().get("consume"));
		assertEquals(1, calls.get());
	}

	@Test
	void preservesBinaryBytesAndAllContentMetadata() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		byte[] bytes = { 0, 1, -1 };
		ContentPart part = new ContentPart(ContentPart.Type.IMAGE, null, "image/png", bytes,
				Map.of("dimensions", Map.of("width", 2)));
		ToolResult result = new ToolResult(List.of(part), false, Map.of("receipt", List.of("r1")));
		AgentOutcome outcome = invoke(result, calls,
				Map.of("data", new WorkflowValue.StepOutput("source", "content.0.data"), "width",
						new WorkflowValue.StepOutput("source", "content.0.metadata.dimensions.width"), "receipt",
						new WorkflowValue.State("source.metadata.receipt.0")));
		Map<?, ?> mapped = assertInstanceOf(Map.class, outcome.outputs().get("consume"));
		assertArrayEquals(bytes, assertInstanceOf(byte[].class, mapped.get("data")));
		assertEquals(2, mapped.get("width"));
		assertEquals("r1", mapped.get("receipt"));
		Map<?, ?> output = assertInstanceOf(Map.class, outcome.outputs().get("source"));
		Map<?, ?> content = assertInstanceOf(Map.class, assertInstanceOf(List.class, output.get("content")).get(0));
		assertEquals("IMAGE", content.get("type"));
		assertEquals("image/png", content.get("mimeType"));
		assertTrue(content.containsKey("text"));
		assertEquals(null, content.get("text"));
		assertEquals(1, calls.get());
	}

	@Test
	void doesNotDiscardMetadataOnSingleText() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		ContentPart part = new ContentPart(ContentPart.Type.TEXT, "text", "text/plain", null, Map.of("language", "en"));
		AgentOutcome outcome = invoke(new ToolResult(List.of(part), false, Map.of("source", "tool")), calls,
				Map.of("text", new WorkflowValue.StepOutput("source", "content.0.text"), "language",
						new WorkflowValue.StepOutput("source", "content.0.metadata.language"), "source",
						new WorkflowValue.StepOutput("source", "metadata.source")));
		assertEquals(Map.of("text", "text", "language", "en", "source", "tool"), outcome.outputs().get("consume"));
		assertEquals(1, calls.get());
	}

	@Test
	void preservesMetadataWhenOnlyOneLevelHasIt() throws Exception {
		for (ToolResult result : List.of(
				new ToolResult(List.of(ContentPart.text("text")), false, Map.of("receipt", "ok")),
				new ToolResult(
						List.of(new ContentPart(ContentPart.Type.TEXT, "text", null, null, Map.of("language", "en"))),
						false, Map.of()))) {
			AtomicInteger calls = new AtomicInteger();
			Map<?, ?> output = assertInstanceOf(Map.class, invoke(result, calls).outputs().get("source"));
			assertEquals(result.metadata(), output.get("metadata"));
			Map<?, ?> part = assertInstanceOf(Map.class, assertInstanceOf(List.class, output.get("content")).get(0));
			assertEquals(result.content().get(0).metadata(), part.get("metadata"));
			assertEquals(1, calls.get());
		}
	}

	@Test
	void representsNullableTextPartWithoutNullStepOutput() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		ToolResult result = new ToolResult(List.of(new ContentPart(ContentPart.Type.TEXT, null, null, null, Map.of())),
				false, Map.of());
		Map<?, ?> output = assertInstanceOf(Map.class, invoke(result, calls).outputs().get("source"));
		Map<?, ?> part = assertInstanceOf(Map.class, assertInstanceOf(List.class, output.get("content")).get(0));
		assertEquals("TEXT", part.get("type"));
		assertTrue(part.containsKey("text"));
		assertEquals(null, part.get("text"));
		assertEquals(1, calls.get());
	}

	@Test
	void reportsMalformedSuccessfulResultWithoutRepeatingToolEffects() {
		AtomicInteger calls = new AtomicInteger();
		ExecutionException failure = assertThrows(ExecutionException.class, () -> invoke(null, calls));
		assertEquals(1, calls.get());
		assertInstanceOf(WorkflowExecutionException.class, failure.getCause());
		assertTrue(failure.getCause().getMessage().contains("result"));
	}

	@Test
	void retainsErrorResultRetryPolicy() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AgentOutcome outcome = invoke(
				tool(calls, attempt -> attempt == 1 ? ToolResult.error("retry") : ToolResult.text("ok")), Map.of());
		assertEquals("ok", outcome.outputs().get("source"));
		assertEquals(2, calls.get());
	}

	@Test
	void failsAfterExhaustedErrorResults() {
		AtomicInteger calls = new AtomicInteger();
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> invoke(ToolResult.error("failure"), calls));
		assertInstanceOf(WorkflowExecutionException.class, failure.getCause());
		assertTrue(failure.getCause().getCause().getMessage().contains("Workflow tool failed"));
		assertEquals(3, calls.get());
	}

	private static AgentOutcome invoke(ToolResult result, AtomicInteger calls) throws Exception {
		return invoke(result, calls, Map.of());
	}

	private static AgentOutcome invoke(ToolResult result, AtomicInteger calls, Map<String, WorkflowValue> inputs)
			throws Exception {
		return invoke(tool(calls, attempt -> result), inputs);
	}

	private static AgentOutcome invoke(Tool tool, Map<String, WorkflowValue> inputs) throws Exception {
		WorkflowStep source = new WorkflowStep.Tool("source", "produce", Map.of(), "source", RETRY);
		WorkflowDefinition definition = new WorkflowDefinition("results", "1", inputs.isEmpty() ? List.of(source)
				: List.of(source, new WorkflowStep.Agent("consume", "consumer", inputs, "consume", null)));
		Agent consumer = (request, context) -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
			public void request(long demand) {
				if (demand > 0) {
					subscriber.onNext(new AgentEvent.Finished(
							AgentOutcome.completed(List.of(), request.inputs(), new Usage(0L, 0L, 0L))));
					subscriber.onComplete();
				}
			}

			public void cancel() {
			}
		});
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.tools(new ToolRegistry(List.of(tool)))
			.agents(Map.of("consumer", consumer))
			.build();
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		try (AgentExecutionContext context = new AgentExecutionContext(trusted, Clock.systemUTC())) {
			return workflow.invoke(new AgentRequest(List.of(), Map.of(), null), context).get(3, TimeUnit.SECONDS);
		}
	}

	private static Tool tool(AtomicInteger calls, IntFunction<ToolResult> results) {
		return new Tool() {
			public ToolDefinition definition() {
				return new ToolDefinition("produce", "produce", "{}");
			}

			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(results.apply(calls.incrementAndGet()));
			}
		};
	}

}
