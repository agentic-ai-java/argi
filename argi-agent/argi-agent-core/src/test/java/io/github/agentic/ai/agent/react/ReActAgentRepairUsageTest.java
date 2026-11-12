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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.agent.result.StructuredOutputFinalizer;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 复现 #207：结构化输出修复调用的用量必须进入交付统计——UsageUpdate 消费者看到的
 * 最终累计值与 Finished 事件的 outcome.usage 都应包含修复调用的消耗，而不是只统计
 * 主调用轮次。
 */
class ReActAgentRepairUsageTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["status"],
			  "additionalProperties": false,
			  "properties": {
			    "status": {"type": "string"}
			  }
			}""";

	private static final Usage MAIN = new Usage(1L, 2L, 3L);

	private static final Usage REPAIR = new Usage(10L, 20L, 30L);

	private static final Usage TOTAL = new Usage(11L, 22L, 33L);

	/**
	 * 验证成功修复后，交付总量（11/22/33）同时出现在最后一个 UsageUpdate 和 Finished
	 * 的 outcome.usage 中，且交付的消息是修复后的消息。
	 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void repairedRunDeliversUsageInclusiveOfRepairCall(boolean streamModel) throws Exception {
		final RepairClient repair = RepairClient.replying("{\"status\":\"ok\"}", REPAIR);
		final UsageView view = run(streamModel, repair);

		assertAll(() -> assertEquals(AgentOutcome.Status.COMPLETED, view.outcome.status()),
				() -> assertEquals(TOTAL, view.visibleUsage, "Final usage update must include the repair call"),
				() -> assertEquals(TOTAL, view.outcome.usage(), "Finished outcome must agree with the usage consumer"),
				() -> assertEquals("{\"status\":\"ok\"}", view.deliveredText, "The repaired message is delivered"));
		assertEquals(1, repair.calls.get(), "Exactly one bounded repair re-ask");
	}

	/**
	 * 验证修复客户端在响应前失败时，交付总量保持未知：没有响应不能证明修复消耗为零，
	 * 事件视图与异常携带的结果都不得退回修复前的 (1,2,3)。
	 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void failedRepairKeepsDeliveryTotalUnknown(boolean streamModel) throws Exception {
		final RepairClient repair = RepairClient.failing(new IllegalStateException("repair backend down"));
		final UsageView view = runExpectingFailure(streamModel, repair);

		assertAll(() -> assertNull(view.outcome, "A failed repair must not publish a successful outcome"),
				() -> assertEquals(Usage.unknown(), view.visibleUsage,
						"No response proves the repair consumption, so the visible total stays unknown"));
	}

	/**
	 * 验证修复响应带用量但输出仍不合法时，合并用量先于失败传播：事件消费者的最后一个
	 * UsageUpdate 与异常携带的 result.usage 一致（11/22/33），不停在修复前的 (1,2,3)。
	 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void repairedButInvalidRunPublishesMergedUsageBeforeFailing(boolean streamModel) throws Exception {
		final RepairClient repair = RepairClient.replying("still not json", REPAIR);
		final UsageView view = runExpectingFailure(streamModel, repair);

		final AgentExecutionException failure = assertInstanceOf(AgentExecutionException.class, view.error);
		assertAll(() -> assertEquals(TOTAL, view.visibleUsage,
				"The merged usage must be published before the failure propagates"),
				() -> assertEquals(TOTAL, failure.result().usage(), "The thrown result carries the merged total"),
				() -> assertEquals(REPAIR, failure.result().repairUsage()),
				() -> assertEquals(AgentResult.Status.VALIDATION_FAILED, failure.result().status()));
	}

	/** 运行单轮无效输出 + 一次修复的完整调用，直到事件流完成。 */
	private UsageView run(boolean streamModel, RepairClient repair) throws Exception {
		final UsageView view = new UsageView();
		final ReActAgent agent = agent(new InvalidJsonClient(streamModel), repair, streamModel);
		try (AgentExecutionContext context = context("repair-usage-test")) {
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("give me the status"))), context)
				.subscribe(view);
			view.completed.get(5, TimeUnit.SECONDS);
		}
		return view;
	}

	/** 运行到以失败终结的事件流，返回已记录失败原因的视图。 */
	private UsageView runExpectingFailure(boolean streamModel, RepairClient repair) throws Exception {
		final UsageView view = new UsageView();
		final ReActAgent agent = agent(new InvalidJsonClient(streamModel), repair, streamModel);
		try (AgentExecutionContext context = context("repair-usage-test")) {
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("give me the status"))), context)
				.subscribe(view);
			assertThrows(ExecutionException.class, () -> view.completed.get(5, TimeUnit.SECONDS));
		}
		return view;
	}

	private ReActAgent agent(ChatClient mainModel, RepairClient repair, boolean streamModel) {
		return ReActAgent.builder(mainModel)
			.streamModel(streamModel)
			.outputSchema(SCHEMA)
			.outputFinalizer(new StructuredOutputFinalizer(repair))
			.build();
	}

	private AgentExecutionContext context(String runId) {
		final TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId(runId)
			.correlationId("repair-usage-correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	/** 主模型单轮返回无效 JSON 终态消息，携带用量 MAIN。 */
	private static final class InvalidJsonClient implements ChatClient {

		private final boolean streaming;

		InvalidJsonClient(boolean streaming) {
			this.streaming = streaming;
		}

		private ChatResponse response() {
			return new ChatResponse("main-id", "test-model", List.of(ChatMessage.assistant("not json")), List.of(),
					FinishReason.STOP, MAIN, Map.of());
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			if (streaming) {
				throw new AssertionError("Expected the streaming model path");
			}
			return CompletableFuture.completedFuture(response());
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			if (!streaming) {
				throw new AssertionError("Expected the non-streaming model path");
			}
			return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
				private final AtomicBoolean requested = new AtomicBoolean();

				private volatile boolean cancelled;

				@Override
				public void request(long demand) {
					if (demand <= 0 || !requested.compareAndSet(false, true) || cancelled) {
						return;
					}
					subscriber.onNext(ChatEvent.textDelta("not json"));
					if (!cancelled) {
						subscriber.onNext(ChatEvent.response(response()));
					}
					if (!cancelled) {
						subscriber.onComplete();
					}
				}

				@Override
				public void cancel() {
					cancelled = true;
				}
			});
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

	/** 修复客户端：脚本化回复文本与用量，或直接失败。 */
	static final class RepairClient implements ChatClient {

		private final AtomicInteger calls = new AtomicInteger();

		private final String repairedText;

		private final Usage usage;

		private final RuntimeException failure;

		private RepairClient(String repairedText, Usage usage, RuntimeException failure) {
			this.repairedText = repairedText;
			this.usage = usage;
			this.failure = failure;
		}

		static RepairClient replying(String text, Usage usage) {
			return new RepairClient(text, usage, null);
		}

		static RepairClient failing(RuntimeException failure) {
			return new RepairClient(null, null, failure);
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			this.calls.incrementAndGet();
			if (this.failure != null) {
				throw this.failure;
			}
			return CompletableFuture.completedFuture(new ChatResponse("repair-id", "test-model",
					List.of(ChatMessage.assistant(this.repairedText)), List.of(), null, this.usage, Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException("Streaming is unavailable");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

	/** 按事件契约维护可见用量与最终 outcome 的消费者视图，失败时记录原因。 */
	private static final class UsageView implements Flow.Subscriber<AgentEvent> {

		private final CompletableFuture<Void> completed = new CompletableFuture<>();

		private Usage visibleUsage = Usage.unknown();

		private AgentOutcome outcome;

		private String deliveredText;

		private Throwable error;

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(AgentEvent event) {
			if (event instanceof AgentEvent.UsageUpdate update) {
				visibleUsage = update.usage();
			}
			else if (event instanceof AgentEvent.Message message) {
				deliveredText = message.message().text();
			}
			else if (event instanceof AgentEvent.Finished finished) {
				outcome = finished.outcome();
				deliveredText = outcome.messages().get(outcome.messages().size() - 1).text();
			}
		}

		@Override
		public void onError(Throwable failure) {
			this.error = failure;
			completed.completeExceptionally(failure);
		}

		@Override
		public void onComplete() {
			if (outcome == null) {
				completed.completeExceptionally(new IllegalStateException("Stream completed without a final outcome"));
			}
			else {
				completed.complete(null);
			}
		}

	}

}
