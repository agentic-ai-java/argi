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
package io.github.agentic.ai.agent.lifecycle;

import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatMessage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the neutral lifecycle extension points.
 *
 * <p>
 * The tests drive the four extension kinds directly, without a model, a tool binding
 * or an engine. The chain executor and the engine wiring are separate issues; what
 * this test pins down is the semantics of the contract itself: observation,
 * restricted transformation, rejection, wrapping and failure handling.
 */
class LifecycleContractTest {

	private static final InvocationId INVOCATION = InvocationId.of("inv-1");

	private static final ModelTurnId MODEL_TURN = ModelTurnId.of("turn-1");

	private static final ToolCallId TOOL_CALL = ToolCallId.of("call-1");

	private static final List<String> NEUTRAL_CLASS_FILES = List.of("LifecycleCapabilities", "LifecycleStage",
			"LifecycleIdentity", "InvocationId", "AttemptId", "StepId", "ModelTurnId", "ToolCallId", "DelegationId",
			"LifecycleEvent", "LifecycleExtension", "LifecycleObserver", "LifecycleTransformer",
			"LifecycleMutationGuard", "AroundInterceptor", "AroundChain", "AdmissionGate", "GateDecision",
			"ModelCallInvocation", "ModelCallOutcome", "ModelCallExecutor", "ToolCallInvocation", "ToolCallOutcome",
			"ToolCallExecutor");

	private static LifecycleIdentity identity() {
		return LifecycleIdentity.builder(INVOCATION)
			.attempt(AttemptId.of("attempt-1"))
			.step(StepId.of("step-1"))
			.modelTurn(MODEL_TURN)
			.toolCall(TOOL_CALL)
			.build();
	}

	private static ToolCallInvocation toolCall(String toolName, String arguments) {
		return new ToolCallInvocation(identity(), TOOL_CALL, toolName, arguments);
	}

	private static ModelCallInvocation modelCall() {
		ModelRequest request = ModelRequest.builder().messages(List.of(ChatMessage.user("hello"))).build();
		return new ModelCallInvocation(identity(), MODEL_TURN, request);
	}

	@Test
	void observerSeesImmutableTypedEvents() {
		List<LifecycleEvent> seen = new ArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public String id() {
				return "audit";
			}

			@Override
			public Set<LifecycleStage> stages() {
				return Set.of(LifecycleStage.TOOL_CALL);
			}

			@Override
			public void onEvent(LifecycleEvent event) {
				seen.add(event);
			}
		};

		StageRunner runner = new StageRunner();
		runner.observers.add(observer);

		LifecycleEvent event = new LifecycleEvent.ToolCallRequested(toolCall("search", "{}"));
		LifecycleEvent result = runner.run(event, e -> new LifecycleEvent.ToolCallCompleted(
				((LifecycleEvent.ToolCallRequested) e).invocation(), ToolCallOutcome.success(TOOL_CALL, "ok")));

		assertEquals(1, seen.size());
		assertEquals(LifecycleStage.TOOL_CALL, seen.get(0).stage());
		assertEquals(identity(), seen.get(0).identity());
		assertTrue(result instanceof LifecycleEvent.ToolCallCompleted);

		assertTrue(LifecycleEvent.ToolCallRequested.class.isRecord());
		for (Field field : LifecycleEvent.ToolCallRequested.class.getDeclaredFields()) {
			if (field.isSynthetic()) {
				continue;
			}
			assertTrue(Modifier.isFinal(field.getModifiers()), "event fields must be final: " + field.getName());
		}
	}

	@Test
	void restrictedTransformerRewritesOnlyAllowedFields() {
		LifecycleTransformer argumentTransformer = transformer("redact", "{\"secret\":true}", "{\"secret\":false}");
		LifecycleEvent original = new LifecycleEvent.ToolCallRequested(toolCall("search", "{\"secret\":true}"));

		Optional<LifecycleEvent> rewritten = argumentTransformer.transform(original);
		assertTrue(rewritten.isPresent());
		assertTrue(LifecycleMutationGuard.check(original, rewritten.get()).isEmpty());

		StageRunner runner = new StageRunner();
		runner.transformers.add(argumentTransformer);
		LifecycleEvent result = runner.run(original, e -> e);

		LifecycleEvent.ToolCallRequested applied = (LifecycleEvent.ToolCallRequested) result;
		assertEquals("{\"secret\":false}", applied.invocation().arguments());
		assertEquals("search", applied.invocation().toolName());
		assertTrue(runner.failures.isEmpty());
	}

	@Test
	void restrictedTransformerRejectsOutOfScopeRewrite() {
		LifecycleTransformer renaming = new LifecycleTransformer() {
			@Override
			public String id() {
				return "rename";
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public Optional<LifecycleEvent> transform(LifecycleEvent event) {
				if (!(event instanceof LifecycleEvent.ToolCallRequested requested)) {
					return Optional.empty();
				}
				return Optional.of(new LifecycleEvent.ToolCallRequested(new ToolCallInvocation(
						requested.invocation().identity(), requested.invocation().toolCall(), "delete",
						requested.invocation().arguments())));
			}
		};
		LifecycleEvent original = new LifecycleEvent.ToolCallRequested(toolCall("search", "{\"secret\":true}"));

		LifecycleEvent rewritten = new LifecycleEvent.ToolCallRequested(toolCall("delete", "{\"secret\":true}"));
		assertEquals("tool name must not change",
				LifecycleMutationGuard.check(original, rewritten).orElseThrow());

		StageRunner runner = new StageRunner();
		runner.transformers.add(renaming);
		LifecycleEvent result = runner.run(original, e -> e);

		assertEquals("search", ((LifecycleEvent.ToolCallRequested) result).invocation().toolName());
		assertEquals("{\"secret\":true}",
				((LifecycleEvent.ToolCallRequested) result).invocation().arguments());
		assertEquals(1, runner.failures.size());
		assertEquals("rename", extensionId(runner.failures.get(0)));
	}

	@Test
	void admissionGateShortCircuitsDeniedStage() {
		AtomicInteger chainCalls = new AtomicInteger();
		AdmissionGate gate = new AdmissionGate() {
			@Override
			public String id() {
				return "policy";
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public GateDecision admit(LifecycleEvent event) {
				return GateDecision.deny("tool is not allowed for this tenant");
			}
		};

		StageRunner runner = new StageRunner();
		runner.gates.add(gate);
		LifecycleEvent result = runner.run(new LifecycleEvent.ToolCallRequested(toolCall("search", "{}")),
				e -> {
					chainCalls.incrementAndGet();
					return e;
				});

		assertEquals(0, chainCalls.get());
		LifecycleEvent.StageExited exited = (LifecycleEvent.StageExited) result;
		assertFalse(exited.successful());
		assertEquals("tool is not allowed for this tenant", exited.failureReason());
	}

	@Test
	void approvalRequestDegradesToDenialWithoutApprovalChannel() {
		AtomicInteger chainCalls = new AtomicInteger();
		AdmissionGate gate = new AdmissionGate() {
			@Override
			public String id() {
				return "approval";
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public GateDecision admit(LifecycleEvent event) {
				return GateDecision.requireApproval("needs a human decision");
			}
		};

		StageRunner runner = new StageRunner();
		runner.gates.add(gate);
		runner.approvalChannelAvailable = false;
		LifecycleEvent result = runner.run(new LifecycleEvent.ToolCallRequested(toolCall("search", "{}")),
				e -> {
					chainCalls.incrementAndGet();
					return e;
				});

		assertEquals(0, chainCalls.get());
		LifecycleEvent.StageExited exited = (LifecycleEvent.StageExited) result;
		assertFalse(exited.successful());
		assertEquals("needs a human decision", exited.failureReason());
	}

	@Test
	void aroundInterceptorCanRewriteShortCircuitAndRetry() {
		AtomicInteger chainCalls = new AtomicInteger();
		AroundInterceptor retrying = new AroundInterceptor() {
			@Override
			public String id() {
				return "retry";
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public LifecycleEvent intercept(LifecycleEvent event, AroundChain chain) {
				chain.proceed(event);
				return chain.proceed(event);
			}
		};

		StageRunner runner = new StageRunner();
		runner.interceptors.add(retrying);
		LifecycleEvent event = new LifecycleEvent.ToolCallRequested(toolCall("search", "{}"));
		LifecycleEvent result = runner.run(event, e -> {
			chainCalls.incrementAndGet();
			return new LifecycleEvent.ToolCallCompleted(((LifecycleEvent.ToolCallRequested) e).invocation(),
					ToolCallOutcome.success(TOOL_CALL, "ok"));
		});

		assertEquals(2, chainCalls.get());
		assertTrue(result instanceof LifecycleEvent.ToolCallCompleted);

		AroundInterceptor shortCircuit = new AroundInterceptor() {
			@Override
			public String id() {
				return "cache";
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public LifecycleEvent intercept(LifecycleEvent event, AroundChain chain) {
				return new LifecycleEvent.ToolCallCompleted(((LifecycleEvent.ToolCallRequested) event).invocation(),
						ToolCallOutcome.success(TOOL_CALL, "cached"));
			}
		};

		AtomicInteger shortCircuitCalls = new AtomicInteger();
		StageRunner shortCircuitRunner = new StageRunner();
		shortCircuitRunner.interceptors.add(shortCircuit);
		LifecycleEvent cached = shortCircuitRunner.run(event, e -> {
			shortCircuitCalls.incrementAndGet();
			return e;
		});

		assertEquals(0, shortCircuitCalls.get());
		assertEquals("cached",
				((LifecycleEvent.ToolCallCompleted) cached).outcome().result());
	}

	@Test
	void failingObserverDoesNotBreakTheStage() {
		AtomicInteger chainCalls = new AtomicInteger();
		LifecycleObserver broken = new LifecycleObserver() {
			@Override
			public String id() {
				return "broken-observer";
			}

			@Override
			public Set<LifecycleStage> stages() {
				return Set.of(LifecycleStage.TOOL_CALL);
			}

			@Override
			public void onEvent(LifecycleEvent event) {
				throw new IllegalStateException("observer exploded");
			}
		};

		StageRunner runner = new StageRunner();
		runner.observers.add(broken);
		LifecycleEvent result = runner.run(new LifecycleEvent.ToolCallRequested(toolCall("search", "{}")), e -> {
			chainCalls.incrementAndGet();
			return e;
		});

		assertEquals(1, chainCalls.get());
		assertEquals(1, runner.failures.size());
		assertEquals("broken-observer", extensionId(runner.failures.get(0)));
		assertEquals(LifecycleStage.TOOL_CALL, runner.failures.get(0).stage());
	}

	@Test
	void failingAroundInterceptorAbortsTheStage() {
		AtomicInteger chainCalls = new AtomicInteger();
		AroundInterceptor broken = new AroundInterceptor() {
			@Override
			public String id() {
				return "broken-interceptor";
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public LifecycleEvent intercept(LifecycleEvent event, AroundChain chain) {
				throw new IllegalStateException("interceptor exploded");
			}
		};

		StageRunner runner = new StageRunner();
		runner.interceptors.add(broken);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> runner.run(new LifecycleEvent.ToolCallRequested(toolCall("search", "{}")), e -> {
					chainCalls.incrementAndGet();
					return e;
				}));

		assertEquals("interceptor exploded", failure.getMessage());
		assertEquals(0, chainCalls.get());
		assertEquals(1, runner.failures.size());
		assertEquals("broken-interceptor", extensionId(runner.failures.get(0)));
	}

	@Test
	void modelCallContractCarriesTheTrustedContextUnchanged() {
		ModelCallInvocation invocation = modelCall();
		assertEquals(MODEL_TURN, invocation.modelTurn());
		assertEquals(identity(), invocation.identity());

		ModelCallOutcome outcome = ModelCallOutcome.success(MODEL_TURN, ModelResponse.of(ChatMessage.assistant("hi")));
		assertTrue(outcome.isSuccess());

		ModelCallExecutor executor = call -> ModelCallOutcome.failure(call.modelTurn(), "model unavailable");
		ModelCallOutcome failed = executor.execute(invocation);
		assertFalse(failed.isSuccess());
		assertEquals("model unavailable", failed.error());
	}

	@Test
	void neutralContractDoesNotDependOnGraphOrLoop() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.github.agentic.ai.workflow.graph.KeyStrategy"));
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName("io.github.agentic.ai.workflow.graph.CompiledGraph"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.github.agentic.ai.workflow.graph.StateGraph"));

		for (String className : NEUTRAL_CLASS_FILES) {
			String resource = "io/github/agentic/ai/agent/lifecycle/" + className + ".class";
			assertNotNull(getClass().getClassLoader().getResource(resource), resource + " must exist");
			String bytecode = readResource(resource);
			assertFalse(bytecode.contains("workflow/graph"), className + " must not reference the graph module");
			assertFalse(bytecode.contains("workflow/loop"), className + " must not reference the loop module");
		}
	}

	private static String extensionId(LifecycleEvent event) {
		return ((LifecycleEvent.ExtensionFailed) event).extensionId();
	}

	private String readResource(String resource) {
		try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(input, resource + " must be readable");
			return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
		catch (IOException e) {
			throw new IllegalStateException("cannot read " + resource, e);
		}
	}

	private static LifecycleTransformer transformer(String id, String from, String to) {
		return new LifecycleTransformer() {
			@Override
			public String id() {
				return id;
			}

			@Override
			public LifecycleStage stage() {
				return LifecycleStage.TOOL_CALL;
			}

			@Override
			public Optional<LifecycleEvent> transform(LifecycleEvent event) {
				if (!(event instanceof LifecycleEvent.ToolCallRequested requested)) {
					return Optional.empty();
				}
				if (!requested.invocation().arguments().equals(from)) {
					return Optional.empty();
				}
				return Optional.of(new LifecycleEvent.ToolCallRequested(new ToolCallInvocation(
						requested.invocation().identity(), requested.invocation().toolCall(), requested.invocation().toolName(),
						to)));
			}
		};
	}

	/**
	 * A reference harness that applies the four extension kinds to one stage.
	 *
	 * <p>
	 * The harness is deliberately part of the test: the chain executor and the engine
	 * wiring are separate issues, and this test pins down the contract semantics only.
	 */
	private static final class StageRunner {

		private final List<LifecycleObserver> observers = new ArrayList<>();

		private final List<LifecycleTransformer> transformers = new ArrayList<>();

		private final List<AdmissionGate> gates = new ArrayList<>();

		private final List<AroundInterceptor> interceptors = new ArrayList<>();

		private final List<LifecycleEvent> failures = new ArrayList<>();

		private boolean approvalChannelAvailable = false;

		LifecycleEvent run(LifecycleEvent event, AroundChain chain) {
			for (LifecycleObserver observer : observers) {
				try {
					observer.onEvent(event);
				}
				catch (RuntimeException e) {
					failures.add(new LifecycleEvent.ExtensionFailed(event.stage(), event.identity(), observer.id(),
							String.valueOf(e.getMessage())));
				}
			}

			for (AdmissionGate gate : gates) {
				if (!gate.stages().contains(event.stage())) {
					continue;
				}
				GateDecision decision = gate.admit(event);
				if (decision.isAllowed()) {
					continue;
				}
				if (decision.requiresApproval() && approvalChannelAvailable) {
					continue;
				}
				return new LifecycleEvent.StageExited(event.stage(), event.identity(), false, decision.reason());
			}

			LifecycleEvent current = event;
			for (LifecycleTransformer transformer : transformers) {
				if (!transformer.stages().contains(current.stage())) {
					continue;
				}
				Optional<LifecycleEvent> rewritten = transformer.transform(current);
				if (rewritten.isEmpty()) {
					continue;
				}
				Optional<String> violation = LifecycleMutationGuard.check(current, rewritten.get());
				if (violation.isPresent()) {
					failures.add(new LifecycleEvent.ExtensionFailed(current.stage(), current.identity(),
							transformer.id(), violation.get()));
					continue;
				}
				current = rewritten.get();
			}

			final LifecycleEvent stageEvent = current;
			for (AroundInterceptor interceptor : interceptors) {
				if (!interceptor.stages().contains(current.stage())) {
					continue;
				}
				AroundChain next = chain;
				chain = e -> {
					try {
						return interceptor.intercept(e, next);
					}
					catch (RuntimeException ex) {
						failures.add(new LifecycleEvent.ExtensionFailed(e.stage(), e.identity(), interceptor.id(),
								String.valueOf(ex.getMessage())));
						throw ex;
					}
				};
			}
			return chain.proceed(stageEvent);
		}

	}

}
