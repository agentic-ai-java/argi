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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.lifecycle.LifecycleEvent;
import io.github.agentic.ai.agent.lifecycle.LifecycleObserver;
import io.github.agentic.ai.agent.lifecycle.LifecycleStage;
import io.github.agentic.ai.api.chat.ChatMessage;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复现 #322：步骤进入/退出事件严格成对，并行分支乱序归属正确，NOOP 零行为差异。 */
class WorkflowStepLifecycleTest {

	private final RecordingObserver observer = new RecordingObserver();

	@Test
	void stepsEmitPairedEnterAndExitEventsInLifoOrder() throws Exception {		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "v"), new Usage(1L, 1L, 2L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker"),
						new WorkflowStep.Tool("mark", "mark", Map.of())));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", agent))
			.tools(new ToolRegistry(List.of(textTool("mark"))))
			.observers(observer)
			.build();
		observer.expectExits(2);

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		}
		observer.awaitExits();

		assertEquals(List.of("ENTER answer", "EXIT answer ok", "ENTER mark", "EXIT mark ok"), observer.events);
	}

	@Test
	void aFailedStepExitsUnsuccessfullyExactlyOnce() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		Agent failing = agent(request -> {
			attempts.incrementAndGet();
			throw new IllegalStateException("boom");
		});
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker", Map.of(), null,
						new WorkflowStep.RetryPolicy(2, java.time.Duration.ZERO))));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", failing))
			.observers(observer)
			.build();
		observer.expectExits(1);

		try (AgentExecutionContext context = context()) {
			workflow.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
		}
		catch (Exception pending) {
			// The exhausted retries surface as an error completion; the events are the point.
		}
		observer.awaitExits();

		assertEquals(2, attempts.get(), "both attempts must have run");
		// Issue #322 acceptance: each step ends exactly once, retries included.
		assertEquals(List.of("ENTER answer", "EXIT answer failed:WorkflowExecutionException: Workflow step failed after 2 attempts"),
				observer.events);
	}

	@Test
	void aCancelledStepExitsUnsuccessfully() throws Exception {
		Agent canceller = (request, agentContext) -> subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						agentContext.control().cancel();
						subscriber.onError(new WorkflowExecutionException("cancelled mid-step"));
					}
				}

				public void cancel() {
				}
			});
		};
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", canceller))
			.observers(observer)
			.build();
		observer.expectExits(1);

		try (AgentExecutionContext context = context()) {
			workflow.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
		}
		catch (Exception pending) {
			// Cancellation surfaces as an error completion; the events are the point.
		}
		observer.awaitExits();

		assertTrue(observer.events.size() >= 2 && observer.events.get(0).equals("ENTER answer")
				&& observer.events.get(1).startsWith("EXIT answer failed"),
				"the cancelled step must enter once and exit unsuccessfully: " + observer.events
						+ " unexpected=" + observer.unexpected.get());
	}

	@Test
	void parallelBranchesKeepTheirAttributionWhenCompletionOrderIsReversed() throws Exception {
		CountDownLatch branchBFinished = new CountDownLatch(1);
		Agent slowFirst = agent(request -> {
			try {
				assertTrue(branchBFinished.await(5, TimeUnit.SECONDS), "branch B must finish first");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while waiting for branch B", e);
			}
			return AgentOutcome.completed(List.of(ChatMessage.assistant("a")), Map.of("branch", "a"),
					new Usage(0L, 0L, 0L));
		});
		Agent fastSecond = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("b")),
				Map.of("branch", "b"), new Usage(0L, 0L, 0L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Parallel("parallel",
						List.of(new WorkflowStep.Agent("left", "slow"), new WorkflowStep.Agent("right", "fast")))));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("slow", slowFirst, "fast", fastSecond))
			.observers(observer)
			.parallelExecutor(java.util.concurrent.Executors.newFixedThreadPool(2))
			.build();
		observer.expectExits(2);
		// Release the slow branch only after the fast branch's exit event was recorded,
		// making the reversed completion order deterministic.
		observer.onExit = stepId -> {
			if (stepId.equals("right")) {
				branchBFinished.countDown();
			}
		};

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(5, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		}
		observer.awaitExits();

		int leftEnter = observer.events.indexOf("ENTER left");
		int leftExit = observer.events.indexOf("EXIT left ok");
		int rightEnter = observer.events.indexOf("ENTER right");
		int rightExit = observer.events.indexOf("EXIT right ok");
		assertTrue(leftEnter >= 0 && leftExit > leftEnter, "branch left must emit a paired event: " + observer.events);
		assertTrue(rightEnter >= 0 && rightExit > rightEnter, "branch right must emit a paired event: " + observer.events);
		assertTrue(rightExit < leftExit, "branch right completes first (reversed order): " + observer.events);
		// The left branch's enter/exit pair runs on one worker thread even though the
		// fan-in merges in declaration order.
		assertEquals(observer.threads.get(leftEnter), observer.threads.get(leftExit), "left pair must share a thread");
		assertEquals(observer.threads.get(rightEnter), observer.threads.get(rightExit), "right pair must share a thread");
		assertTrue(!observer.threads.get(leftEnter).equals(Thread.currentThread().getName()),
				"branch events must come from the branch worker thread, not the caller");
	}

	@Test
	void loopIterationsEmitOnePairPerIteration() throws Exception {
		AtomicInteger toggles = new AtomicInteger();
		Tool toggle = new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition("toggle", "toggle", "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call,
					ToolContext context) {
				// First pass keeps the loop condition truthy, the second one ends it.
				return CompletableFuture.completedFuture(ToolResult.text(toggles.incrementAndGet() == 1 ? "true" : "false"));
			}
		};
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Loop("repeat", WorkflowValue.state("runLoop"),
						new WorkflowStep.Tool("toggle", "toggle", Map.of(), "runLoop", null), 3)));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.tools(new ToolRegistry(List.of(toggle)))
			.observers(observer)
			.build();
		observer.expectExits(3);

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of("runLoop", true), null), context)
				.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		}
		observer.awaitExits();

		// The Loop container emits its own pair around the body pairs (nested LIFO).
		assertEquals(List.of("ENTER repeat", "ENTER toggle", "EXIT toggle ok", "ENTER toggle", "EXIT toggle ok",
				"EXIT repeat ok"), observer.events);
	}

	@Test
	void aSuspensionExitsOnceAndTheResumeDoesNotDoubleExit() throws Exception {
		Agent suspending = agent(request -> request.resumeToken() != null
				? AgentOutcome.completed(List.of(ChatMessage.assistant("resumed")), Map.of("value", "done"),
						new Usage(0L, 0L, 0L))
				: new AgentOutcome(AgentOutcome.Status.SUSPENDED, List.of(), Map.of(), new Usage(0L, 0L, 0L),
						new AgentOutcome.Suspension("agent-token", Map.of())));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", suspending))
			.observers(observer)
			.build();
		observer.expectExits(1);
		String resumeToken;

		try (AgentExecutionContext context = context()) {
			AgentOutcome suspended = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.SUSPENDED, suspended.status());
			// The workflow wraps the suspension with its own opaque token.
			resumeToken = suspended.suspension().resumeToken();
		}

		try (AgentExecutionContext context = context()) {
			AgentOutcome completed = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), resumeToken), context)
				.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, completed.status());
		}
		observer.awaitExits();

		assertEquals(List.of("ENTER answer", "EXIT answer ok"), observer.events);
	}

	@Test
	void independentRunsDoNotInheritEachOthersEvents() throws Exception {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "v"), new Usage(1L, 1L, 2L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", agent))
			.observers(observer)
			.build();
		observer.expectExits(2);

		try (AgentExecutionContext context = context()) {
			workflow.invoke(new AgentRequest(List.of(ChatMessage.user("first")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
		}

		try (AgentExecutionContext context = context()) {
			workflow.invoke(new AgentRequest(List.of(ChatMessage.user("second")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
		}
		observer.awaitExits();

		assertEquals(List.of("ENTER answer", "EXIT answer ok", "ENTER answer", "EXIT answer ok"), observer.events);
	}

	private static Agent agent(java.util.function.Function<AgentRequest, AgentOutcome> function) {
		return (request, context) -> subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long demand) {
					if (demand > 0) {
						subscriber.onNext(new AgentEvent.Finished(function.apply(request)));
						subscriber.onComplete();
					}
				}

				public void cancel() {
				}
			});
		};
	}

	private static Tool textTool(String name) {
		return new Tool() {
			public io.github.agentic.ai.api.chat.ToolDefinition definition() {
				return new io.github.agentic.ai.api.chat.ToolDefinition(name, name, "{}");
			}

			public CompletionStage<ToolResult> execute(io.github.agentic.ai.api.chat.ToolCall call,
					ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("recorded"));
			}
		};
	}

	private static AgentExecutionContext context() {
		TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("run")
			.correlationId("correlation")
			.budget(ExecutionBudget.unlimited())
			.delegationScope(DelegationScope.none())
			.build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	@Test
	void aThrowingEnterObserverDoesNotPreventTheStepOrUnpairOthers() throws Exception {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "v"), new Usage(1L, 1L, 2L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", agent))
			.observers(new ThrowingObserver(true, false), observer)
			.build();
		observer.expectExits(1);

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			// The business step must run even though the enter callback blew up.
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		}
		observer.awaitExits();

		assertAll(() -> assertEquals(List.of("ENTER answer", "EXIT answer ok"), observer.events, "pairing intact"),
				() -> assertEquals(1, observer.extensionFailures.size(), "the failure must be reported"),
				() -> assertEquals("throwing-observer", observer.extensionFailures.get(0).extensionId()));
	}

	@Test
	void aThrowingExitObserverSendsExactlyOneBusinessExitToOthers() throws Exception {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "v"), new Usage(1L, 1L, 2L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", agent))
			.observers(new ThrowingObserver(false, true), observer)
			.build();
		observer.expectExits(1);

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			// The throwing exit must not turn into a business error or a second exit.
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		}
		observer.awaitExits();

		assertAll(() -> assertEquals(List.of("ENTER answer", "EXIT answer ok"), observer.events,
				"exactly one business exit, no synthesized failure exit"),
				() -> assertEquals(1, observer.extensionFailures.size()));
	}

	@Test
	void observersBoundToOtherStagesAreNotInvoked() throws Exception {
		Agent agent = agent(request -> AgentOutcome.completed(List.of(ChatMessage.assistant("done")),
				Map.of("value", "v"), new Usage(1L, 1L, 2L)));
		WorkflowDefinition definition = new WorkflowDefinition("demo", "1",
				List.of(new WorkflowStep.Agent("answer", "worker")));
		WorkflowAgent workflow = WorkflowAgent.builder(definition)
			.agents(Map.of("worker", agent))
			.observers(new OtherStageObserver())
			.build();

		try (AgentExecutionContext context = context()) {
			AgentOutcome outcome = workflow
				.invoke(new AgentRequest(List.of(ChatMessage.user("hello")), Map.of(), null), context)
				.get(3, TimeUnit.SECONDS);
			assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
		}

		assertTrue(OtherStageObserver.seen.isEmpty(), "an observer bound to other stages must not be invoked");
	}

	static final class RecordingObserver implements LifecycleObserver {

		final List<String> events = new CopyOnWriteArrayList<>();

		final List<String> threads = new CopyOnWriteArrayList<>();

		final List<LifecycleEvent.ExtensionFailed> extensionFailures = new CopyOnWriteArrayList<>();

		final AtomicReference<Throwable> unexpected = new AtomicReference<>();

		/** Gate the test thread until the expected number of exits has been recorded. */
		private volatile CountDownLatch exitGate;

		/** Deterministic hook invoked with the step id after an exit event was recorded. */
		volatile java.util.function.Consumer<String> onExit;

		void expectExits(int count) {
			this.exitGate = new CountDownLatch(count);
		}

		void awaitExits() throws InterruptedException {
			assertTrue(this.exitGate.await(5, TimeUnit.SECONDS), "expected exits were not recorded: " + this.events);
		}

		@Override
		public java.util.Set<LifecycleStage> stages() {
			return java.util.Set.of(LifecycleStage.WORKFLOW_STEP);
		}

		@Override
		public String id() {
			return "recording-observer";
		}

		@Override
		public void onEvent(LifecycleEvent event) {
			try {
				if (event instanceof LifecycleEvent.StageEntered entered) {
					this.events.add("ENTER " + entered.identity().step().value());
					this.threads.add(Thread.currentThread().getName());
				}
				else if (event instanceof LifecycleEvent.StageExited exited) {
					this.events.add("EXIT " + exited.identity().step().value()
							+ (exited.successful() ? " ok" : " failed:" + exited.failureReason()));
					this.threads.add(Thread.currentThread().getName());
					java.util.function.Consumer<String> hook = this.onExit;
					if (hook != null) {
						hook.accept(exited.identity().step().value());
					}
					CountDownLatch gate = this.exitGate;
					if (gate != null) {
						gate.countDown();
					}
				}
				else if (event instanceof LifecycleEvent.ExtensionFailed failed) {
					this.extensionFailures.add(failed);
				}
			}
			catch (Throwable error) {
				this.unexpected.compareAndSet(null, error);
			}
		}

	}

	/** An observer whose enter or exit callback throws (issue #370 review isolation). */
	static final class ThrowingObserver implements LifecycleObserver {

		private final boolean throwOnEnter;

		private final boolean throwOnExit;

		ThrowingObserver(boolean throwOnEnter, boolean throwOnExit) {
			this.throwOnEnter = throwOnEnter;
			this.throwOnExit = throwOnExit;
		}

		@Override
		public java.util.Set<LifecycleStage> stages() {
			return java.util.Set.of(LifecycleStage.WORKFLOW_STEP);
		}

		@Override
		public String id() {
			return "throwing-observer";
		}

		@Override
		public void onEvent(LifecycleEvent event) {
			if (event instanceof LifecycleEvent.StageEntered && this.throwOnEnter) {
				throw new IllegalStateException("enter boom");
			}
			if (event instanceof LifecycleEvent.StageExited && this.throwOnExit) {
				throw new IllegalStateException("exit boom");
			}
		}

	}

	/** An observer bound to a stage the workflow never emits. */
	static final class OtherStageObserver implements LifecycleObserver {

		static final List<LifecycleEvent> seen = new CopyOnWriteArrayList<>();

		@Override
		public java.util.Set<LifecycleStage> stages() {
			return java.util.Set.of(LifecycleStage.MODEL_CALL);
		}

		@Override
		public String id() {
			return "other-stage-observer";
		}

		@Override
		public void onEvent(LifecycleEvent event) {
			seen.add(event);
		}

	}

}
