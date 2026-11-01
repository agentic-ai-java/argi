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
package io.github.agentic.ai.agent.react;

import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 复现 #279：按覆盖语义消费 UsageUpdate 时，应显示整个调用的累计用量。 */
class ReActAgentCumulativeUsageTest {

	private static final Usage FIRST = new Usage(1L, 1L, 2L);

	private static final Usage SECOND = new Usage(2L, 1L, 3L);

	private static final Usage TOTAL = new Usage(3L, 2L, 5L);

	/** 验证单轮调用的用量事件与最终结果一致，作为正常行为对照。 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void singleTurnConsumerDisplaysReportedUsage(boolean streamModel) throws Exception {
		final UsageView view = run(streamModel, List.of(turn(FIRST, false, List.of(FIRST))));

		assertView(view, FIRST, List.of(FIRST));
	}

	/** 验证两轮分别消耗 2、3 tokens 后，消费者显示累计 5，而不是最后一轮的 3。 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void multipleTurnsConsumerDisplaysInvocationTotal(boolean streamModel) throws Exception {
		final UsageView view = run(streamModel,
				List.of(turn(FIRST, true, List.of(FIRST)), turn(SECOND, false, List.of(SECOND))));

		assertView(view, TOTAL, List.of(FIRST, TOTAL));
	}

	/** 验证同一流式轮次的重复快照不会重复计费，新轮快照应叠加到已完成轮次上。 */
	@Test
	void repeatedStreamingSnapshotsReplaceCurrentTurnUsage() throws Exception {
		final Usage partial = new Usage(1L, 0L, 1L);
		final UsageView view = run(true, List.of(turn(FIRST, true, List.of(partial, FIRST, FIRST)),
				turn(SECOND, false, List.of(partial, SECOND, SECOND))));

		assertView(view, TOTAL, List.of(partial, FIRST, new Usage(2L, 1L, 3L), TOTAL));
	}

	/** 验证流式模型仅在最终响应中提供用量时，消费者仍能得到累计结果。 */
	@Test
	void responseOnlyStreamsStillPublishCumulativeUsage() throws Exception {
		final UsageView view = run(true,
				List.of(turn(FIRST, true, List.of()), turn(SECOND, false, List.of())));

		assertView(view, TOTAL, List.of(FIRST, TOTAL));
	}

	/** 验证最终响应补全本轮用量时，覆盖先前的部分快照而不是额外累加。 */
	@Test
	void finalResponsesRefreshPartialStreamingSnapshots() throws Exception {
		final Usage partial = new Usage(1L, 0L, 1L);
		final UsageView view = run(true,
				List.of(turn(FIRST, true, List.of(partial)), turn(SECOND, false, List.of(partial))));

		assertView(view, TOTAL, List.of(partial, FIRST, new Usage(2L, 1L, 3L), TOTAL));
	}

	/** 验证某轮字段未知时，累计值对应字段仍未知，已知字段则继续累计。 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void partiallyUnknownUsageStaysUnknownAcrossTurns(boolean streamModel) throws Exception {
		final Usage partial = new Usage(null, 1L, null);
		final Usage expected = new Usage(null, 2L, null);
		final UsageView view = run(streamModel,
				List.of(turn(partial, true, List.of(partial)), turn(SECOND, false, List.of(SECOND))));

		assertView(view, expected, List.of(partial, expected));
	}

	/** 验证前一轮用量完全未知时，后一轮已知用量不能冒充整个调用的精确总量。 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void unknownTurnDoesNotBecomeAnExactTotal(boolean streamModel) throws Exception {
		final UsageView view = run(streamModel, List.of(turn(Usage.unknown(), true, List.of(Usage.unknown())),
				turn(SECOND, false, List.of(SECOND))));

		assertView(view, Usage.unknown(), List.of(Usage.unknown()));
	}

	/** 验证同一个 Agent 的两个调用同时执行时，用量事件和最终结果互不串用。 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void overlappingRunsKeepSeparateUsage(boolean streamModel) throws Exception {
		final CyclicBarrier bothModelsStarted = new CyclicBarrier(2);
		final Usage otherUsage = new Usage(4L, 3L, 7L);
		final ChatClient client = new ChatClient() {
			private ScriptedClient clientFor(ChatRequest request) {
				try {
					bothModelsStarted.await(5, TimeUnit.SECONDS);
				}
				catch (Exception error) {
					throw new IllegalStateException("Both model calls must overlap", error);
				}
				final Usage usage = request.messages().get(0).text().equals("first") ? FIRST : otherUsage;
				return new ScriptedClient(streamModel, List.of(turn(usage, false, List.of(usage))));
			}

			@Override
			public CompletionStage<ChatResponse> call(ChatRequest request) {
				return clientFor(request).call(request);
			}

			@Override
			public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
				return clientFor(request).stream(request);
			}

			@Override
			public ChatClientCapabilities capabilities() {
				return ChatClientCapabilities.basic();
			}
		};
		final ReActAgent agent = agent(client, streamModel);
		final UsageView first = new UsageView();
		final UsageView second = new UsageView();
		try (AgentExecutionContext firstContext = context("first");
				AgentExecutionContext secondContext = context("second")) {
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("first"))), firstContext).subscribe(first);
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("second"))), secondContext).subscribe(second);
			CompletableFuture.allOf(first.completed, second.completed).get(10, TimeUnit.SECONDS);
		}

		assertView(first, FIRST, List.of(FIRST));
		assertView(second, otherUsage, List.of(otherUsage));
	}

	/** 验证后续轮次失败时，只保留已报告的用量，不猜测未返回的消耗或伪造成功终态。 */
	@ParameterizedTest(name = "streamModel={0}")
	@ValueSource(booleans = { false, true })
	void failedTurnKeepsOnlyReportedUsage(boolean streamModel) throws Exception {
		final Usage partial = new Usage(1L, 0L, 1L);
		final ModelTurn failed = new ModelTurn(null, streamModel ? List.of(partial) : List.of(),
				new IllegalStateException("Model failed before returning its final usage"));
		final ReActAgent agent = agent(new ScriptedClient(streamModel,
				List.of(turn(FIRST, true, List.of(FIRST)), failed)), streamModel);
		final UsageView view = new UsageView();
		try (AgentExecutionContext context = context("failed")) {
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("Look up a value"))), context).subscribe(view);
			assertThrows(ExecutionException.class, () -> view.completed.get(5, TimeUnit.SECONDS));
		}

		assertNull(view.outcome, "Failed model calls must not publish a successful final outcome");
		assertEquals(streamModel ? new Usage(2L, 1L, 3L) : FIRST, view.visibleUsage);
		assertEquals(streamModel ? List.of(FIRST, new Usage(2L, 1L, 3L)) : List.of(FIRST), view.history);
	}

	/** 运行真实 ReAct 工具循环，直到事件流完成，模型和工具均使用本地固定响应。 */
	private UsageView run(boolean streamModel, List<ModelTurn> turns) throws Exception {
		final ReActAgent agent = agent(new ScriptedClient(streamModel, turns), streamModel);
		final UsageView view = new UsageView();
		try (AgentExecutionContext context = context("usage-test")) {
			agent.stream(AgentRequest.messages(List.of(ChatMessage.user("Look up a value"))), context).subscribe(view);
			view.completed.get(5, TimeUnit.SECONDS);
		}
		return view;
	}

	/** 创建带本地查询工具的 Agent，支持流式和非流式模型调用。 */
	private ReActAgent agent(ChatClient client, boolean streamModel) {
		final Tool lookup = new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition("lookup", "Local lookup", "{\"type\":\"object\"}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("lookup result"));
			}
		};
		return ReActAgent.builder(client).streamModel(streamModel)
			.tools(new ToolRegistry(List.of(lookup))).build();
	}

	/** 为每次调用创建独立执行上下文，关闭时清理其运行控制。 */
	private AgentExecutionContext context(String runId) {
		final TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId(runId).correlationId("usage-correlation").budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	/** 同时检查覆盖显示值、可见快照变化和最终 outcome，避免只检查内部累计结果。 */
	private static void assertView(UsageView view, Usage expected, List<Usage> history) {
		assertAll(() -> assertEquals(AgentOutcome.Status.COMPLETED, view.outcome.status()),
				() -> assertEquals(expected, view.visibleUsage, "UsageUpdate consumer must display invocation totals"),
				() -> assertEquals(history, view.history, "Visible snapshots must include completed turns"),
				() -> assertEquals(expected, view.outcome.usage(), "Final outcome must agree with the usage consumer"));
	}

	/** 创建一个模型轮次，可选择返回工具调用，并指定该轮流式用量快照。 */
	private static ModelTurn turn(Usage usage, boolean callsTool, List<Usage> snapshots) {
		final List<ToolCall> calls = callsTool ? List.of(new ToolCall("lookup-1", "lookup", "{}")) : List.of();
		final ChatMessage message = ChatMessage.assistant(callsTool ? "use lookup" : "final answer", calls);
		return new ModelTurn(new ChatResponse(null, "test", List.of(message), calls, FinishReason.STOP, usage,
				Map.of()), List.copyOf(snapshots), null);
	}

	private record ModelTurn(ChatResponse response, List<Usage> snapshots, RuntimeException failure) {
	}

	private static final class UsageView implements Flow.Subscriber<AgentEvent> {

		private final CompletableFuture<Void> completed = new CompletableFuture<>();

		private final List<Usage> history = new ArrayList<>();

		private Usage visibleUsage = Usage.unknown();

		private AgentOutcome outcome;

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		/** 按事件契约覆盖显示值，重复的相同快照不产生新的显示变化。 */
		@Override
		public void onNext(AgentEvent event) {
			if (event instanceof AgentEvent.UsageUpdate update) {
				visibleUsage = update.usage();
				if (history.isEmpty() || !history.get(history.size() - 1).equals(visibleUsage)) {
					history.add(visibleUsage);
				}
			}
			else if (event instanceof AgentEvent.Finished finished) {
				outcome = finished.outcome();
			}
		}

		@Override
		public void onError(Throwable error) {
			completed.completeExceptionally(error);
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

	private static final class ScriptedClient implements ChatClient {

		private final boolean streaming;

		private final Queue<ModelTurn> turns;

		private ScriptedClient(boolean streaming, List<ModelTurn> turns) {
			this.streaming = streaming;
			this.turns = new ArrayDeque<>(turns);
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			if (streaming) {
				throw new AssertionError("Expected the streaming model path");
			}
			final ModelTurn turn = turns.remove();
			return turn.failure() == null ? CompletableFuture.completedFuture(turn.response())
					: CompletableFuture.failedFuture(turn.failure());
		}

		/** 按需求发布当前轮快照和最终响应，重复快照由测试场景显式给定。 */
		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			if (!streaming) {
				throw new AssertionError("Expected the non-streaming model path");
			}
			final ModelTurn turn = turns.remove();
			return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
				private final AtomicBoolean requested = new AtomicBoolean();

				private volatile boolean cancelled;

				@Override
				public void request(long demand) {
					if (demand <= 0 || !requested.compareAndSet(false, true) || cancelled) {
						return;
					}
					for (Usage usage : turn.snapshots()) {
						if (cancelled) {
							return;
						}
						subscriber.onNext(ChatEvent.usage(usage));
					}
					if (!cancelled) {
						if (turn.failure() != null) {
							subscriber.onError(turn.failure());
							return;
						}
						subscriber.onNext(ChatEvent.response(turn.response()));
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

}
