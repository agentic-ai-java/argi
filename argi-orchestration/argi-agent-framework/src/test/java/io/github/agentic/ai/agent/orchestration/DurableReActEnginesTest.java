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
package io.github.agentic.ai.agent.orchestration;

import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.agent.durable.MissingResponsePolicy;
import io.github.agentic.ai.agent.durable.ModelTurnAttemptPolicy;
import io.github.agentic.ai.agent.durable.ModelTurnBindings;
import io.github.agentic.ai.agent.durable.ModelTurnCodec;
import io.github.agentic.ai.agent.durable.DurableSession;
import io.github.agentic.ai.agent.durable.ToolTaskIdentity;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.react.ToolContextPatchProvider;
import io.github.agentic.ai.agent.orchestration.tools.task.TaskRepository;
import io.github.agentic.ai.agent.orchestration.tools.task.TaskTool;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.ExecutionGuard;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.runtime.execution.definition.DefinitionKind;
import io.github.agentic.ai.runtime.execution.definition.DefinitionReference;
import io.github.agentic.ai.runtime.execution.run.MemoryRunStore;
import io.github.agentic.ai.runtime.execution.run.RunCommit;
import io.github.agentic.ai.runtime.execution.run.RunCommitCoordinator;
import io.github.agentic.ai.runtime.execution.run.RunLease;
import io.github.agentic.ai.runtime.execution.run.RunPayload;
import io.github.agentic.ai.runtime.execution.run.RunSnapshot;
import io.github.agentic.ai.runtime.execution.run.RunSpec;
import io.github.agentic.ai.runtime.execution.run.RunState;
import io.github.agentic.ai.runtime.execution.run.RunStore;
import io.github.agentic.ai.workflow.graph.NodeOutput;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The same recovery/commit contract exercised through both real ReAct engines. */
@Timeout(20)
class DurableReActEnginesTest {

	enum Engine {

		INDEPENDENT, GRAPH

	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void ordinaryCycleAndTerminalReplayPreserveUsageAndTaskIdentity(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		Result first = fixture.run(engine);
		assertThat(first.text()).isEqualTo("done");
		assertThat(first.usage()).isEqualTo(new Usage(4L, 2L, 6L));
		assertThat(first.stateValue()).isEqualTo(2);
		assertThat(fixture.modelCalls.get()).isEqualTo(2);
		assertThat(fixture.tasks).extracting(ToolTaskIdentity::ordinal).containsExactly(0, 1);
		assertThat(fixture.arguments).containsExactly("{\"q\":1}", "{\"q\":2}");
		assertThat(fixture.snapshot().state()).isEqualTo(RunState.SUCCEEDED);
		for (ToolTaskIdentity identity : fixture.tasks) {
			assertThat(fixture.snapshot().journal()).containsKey(identity.entryId());
		}
		Result replayed = fixture.run(engine);
		assertThat(replayed).isEqualTo(first);
		assertThat(fixture.modelCalls.get()).isEqualTo(2);
		assertThat(fixture.tasks).hasSize(2);
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void rebuildAfterPlanCommitUsesRecordedPlanAndReconstructedDynamicBindings(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.dynamic = true;
		fixture.failAfter = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
		assertThat(fixture.tasks).isEmpty();
		assertThat(fixture.transforms.get()).isEqualTo(1);
		List<String> plannedIds = fixture.snapshot()
			.journal()
			.keySet()
			.stream()
			.filter(id -> id.contains("#task."))
			.sorted()
			.toList();
		fixture.failAfter = commit -> false;
		assertThat(fixture.run(engine).text()).isEqualTo("done");
		assertThat(fixture.planningCalls.get()).isEqualTo(1);
		assertThat(fixture.transforms.get()).isEqualTo(2);
		assertThat(fixture.tasks).extracting(ToolTaskIdentity::entryId).containsExactlyElementsOf(plannedIds);
		assertThat(fixture.arguments).containsExactly("{\"q\":1}", "{\"q\":2}");
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void reservationFailureNeverCallsModel(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.failBefore = commit -> contains(commit, ModelTurnCodec.ATTEMPT_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		assertThat(fixture.modelCalls.get()).isZero();
		assertThat(fixture.tasks).isEmpty();
		assertThat(fixture.deliveredAssistantMessages).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void responseCommitFailureNeverDispatchesOrDeliversResponse(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.failBefore = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
		assertThat(fixture.tasks).isEmpty();
		assertThat(fixture.deliveredAssistantMessages).isEmpty();
		fixture.failBefore = commit -> false;
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("Recovery re-asking is disabled");
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void explicitReaskUsesAnotherReservationWithoutChangingLogicalTaskIds(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.policy = new ModelTurnAttemptPolicy(MissingResponsePolicy.REASK, 2);
		fixture.failBefore = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		fixture.failBefore = commit -> false;
		fixture.run(engine);
		assertThat(fixture.planningCalls.get()).isEqualTo(2);
		assertThat(fixture.tasks).hasSize(2).allSatisfy(task -> assertThat(task.turnId()).endsWith("/0"));
		assertThat(fixture.snapshot().journal().keySet()).anyMatch(id -> id.endsWith("/0#attempt.2"));
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void exhaustedReservationsStayExhaustedAfterRebuild(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.policy = new ModelTurnAttemptPolicy(MissingResponsePolicy.REASK, 2);
		fixture.failBefore = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		fixture.failBefore = commit -> false;
		assertThatThrownBy(() -> fixture.run(engine)).isInstanceOf(RuntimeException.class);
		assertThat(fixture.modelCalls.get()).isEqualTo(2);
		assertThat(fixture.tasks).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void uncertainSideEffectBatchDoesNotDispatchAgain(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.replayable = false;
		fixture.failBefore = commit -> !fixture.tasks.isEmpty() && commit.journal().isEmpty();
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		assertThat(fixture.tasks).hasSize(2);
		fixture.failBefore = commit -> false;
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("receipt reconciliation");
		assertThat(fixture.tasks).hasSize(2);
		assertThat(fixture.planningCalls.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void fixedBindingVersionMustMatchOnRecovery(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.failAfter = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).isInstanceOf(RuntimeException.class);
		fixture.catalogVersion = "2";
		fixture.failAfter = commit -> false;
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("binding or attempt policy changed");
		assertThat(fixture.tasks).isEmpty();
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void committedResponseContainsTheTransformationAndDoesNotTransformAgain(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.transformResponse = true;
		fixture.failAfter = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		fixture.failAfter = commit -> false;
		assertThat(fixture.run(engine).text()).isEqualTo("done-reviewed");
		assertThat(fixture.transforms.get()).isEqualTo(2);
		assertThat(fixture.deliveredAssistantMessages).contains("checking-reviewed");
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void reaskRejectsChangedEffectiveRequestBeforeCallingProvider(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.policy = new ModelTurnAttemptPolicy(MissingResponsePolicy.REASK, 2);
		fixture.failBefore = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		fixture.requestRevision = "changed";
		fixture.failBefore = commit -> false;
		assertThatThrownBy(() -> fixture.run(engine)).isInstanceOf(RuntimeException.class);
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
		assertThat(fixture.tasks).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void replayableBatchCanResumeAfterItsResultsFailedToCommit(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.failBefore = commit -> !fixture.tasks.isEmpty() && commit.journal().isEmpty();
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		fixture.failBefore = commit -> false;
		assertThat(fixture.run(engine).stateValue()).isEqualTo(2);
		assertThat(fixture.tasks.subList(2, 4)).containsExactlyElementsOf(fixture.tasks.subList(0, 2));
		assertThat(fixture.planningCalls.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void streamMustCompleteNormallyBeforeCommitAndDelivery(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.streaming = true;
		fixture.streamFailure = true;
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("stream failed after response");
		assertThat(fixture.tasks).isEmpty();
		assertThat(fixture.deliveredAssistantMessages).isEmpty();
		assertThat(fixture.snapshot().journal().values())
			.noneMatch(entry -> entry.payload().type().equals(ModelTurnCodec.RESPONSE_TYPE));
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void streamResponseIsNotDeliveredWhenItsCommitFails(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.streaming = true;
		fixture.failBefore = commit -> contains(commit, ModelTurnCodec.RESPONSE_TYPE);
		assertThatThrownBy(() -> fixture.run(engine)).hasMessageContaining("injected storage failure");
		assertThat(fixture.deliveredAssistantMessages).isEmpty();
		assertThat(fixture.deltas.get()).isZero();
		assertThat(fixture.tasks).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void ownershipLossAfterPlanCommitStopsToolDispatch(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.afterCommit = commit -> {
			if (contains(commit, ModelTurnCodec.RESPONSE_TYPE)) {
				fixture.activeContext.control().loseOwnership();
			}
		};
		assertThatThrownBy(() -> fixture.run(engine)).isInstanceOf(RuntimeException.class);
		assertThat(fixture.tasks).isEmpty();
		assertThat(fixture.deliveredAssistantMessages).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void streamCollectsInternallyAndOnlyDeliversCommittedMessages(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.streaming = true;
		fixture.run(engine);
		assertThat(fixture.streamCalls.get()).isEqualTo(2);
		assertThat(fixture.deliveredAssistantMessages).isNotEmpty();
		assertThat(fixture.deltas.get()).isZero();
	}

	@ParameterizedTest
	@EnumSource(Engine.class)
	void returnDirectFinishesWithoutAnotherModelCall(Engine engine) throws Exception {
		Fixture fixture = new Fixture();
		fixture.direct = true;
		String expected = engine == Engine.GRAPH ? "[\"tool-result\",\"tool-result\"]" : "tool-result";
		assertThat(fixture.run(engine).text()).isEqualTo(expected);
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
		assertThat(fixture.run(engine).text()).isEqualTo(expected);
		assertThat(fixture.modelCalls.get()).isEqualTo(1);
	}

	@Test
	void parallelGraphToolsRetainResponseOrdinals() throws Exception {
		Fixture fixture = new Fixture();
		fixture.parallel = true;
		fixture.run(Engine.GRAPH);
		assertThat(fixture.tasks).extracting(ToolTaskIdentity::ordinal).containsExactlyInAnyOrder(0, 1);
	}

	@Test
	void graphRejectsAgentToolDelegationWithoutReplayingTheParentPlan() throws Exception {
		ChatClient childClient = mock(ChatClient.class);
		ReactAgent child = ReactAgent.builder().name("child").chatClient(childClient).build();

		assertDelegationRejected(AgentTool.create(child), """
				{"input":"child prompt"}
				""", childClient);
	}

	@ParameterizedTest(name = "tracing={0}, background={1}")
	@CsvSource({ "false, false", "false, true", "true, false", "true, true" })
	void graphRejectsTaskToolDelegationBeforeStartingOrEnqueueingTheChild(boolean tracing, boolean background)
			throws Exception {
		ChatClient childClient = mock(ChatClient.class);
		ReactAgent child = ReactAgent.builder().name("child").chatClient(childClient).build();
		TaskRepository repository = mock(TaskRepository.class);
		AgentTracer tracer = tracing ? new AgentTracer(ObservationRegistry.create()) : null;
		Tool task = TaskTool.builder()
			.subAgent("child", child)
			.taskRepository(repository)
			.agentTracer(tracer)
			.build();

		assertDelegationRejected(task, """
				{"description":"Delegate a task","prompt":"child prompt","subagent_type":"child","run_in_background":%s}
				""".formatted(background), childClient);
		verifyNoInteractions(repository);
	}

	private void assertDelegationRejected(Tool tool, String arguments, ChatClient childClient) throws Exception {
		Fixture fixture = new Fixture();
		ToolCall delegation = new ToolCall("delegation", tool.definition().name(), arguments);
		Result result = fixture.run(Engine.GRAPH, List.of(tool), List.of(delegation));

		assertThat(result.text()).isEqualTo("done");
		assertThat(fixture.toolMessages).filteredOn(message -> delegation.id().equals(message.toolCallId()))
			.singleElement().satisfies(message -> {
				assertThat(message.text()).contains("Sub-agent delegation is not supported in durable execution");
				assertThat(message.metadata()).containsEntry("error", true);
			});
		assertThat(fixture.tasks).extracting(ToolTaskIdentity::ordinal).containsExactly(0, 1);
		assertThat(fixture.planningCalls.get()).isEqualTo(1);
		verify(childClient, never()).call(any());
		verify(childClient, never()).stream(any());
	}

	@Test
	void graphDoesNotReadOrWriteTheLegacySaverInTheRecordedRun() throws Exception {
		Fixture fixture = new Fixture();
		fixture.run(Engine.GRAPH);
		fixture.run(Engine.GRAPH);
		org.mockito.Mockito.verifyNoInteractions(fixture.legacySaver);
	}

	@Test
	void memoryBackendDoesNotPassProductionDurabilityAdmission() {
		Fixture fixture = new Fixture();
		try (AgentExecutionContext context = fixture.context()) {
			var coordinator = new RunCommitCoordinator(fixture.store, fixture.snapshot(), () -> fixture.lease,
					context.control());
			assertThatThrownBy(() -> DurableSession.durable(coordinator,
					fixture.bindings(new ScriptedClient(fixture), new ToolRegistry(List.of())), fixture.policy,
					List.of()))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	private static boolean contains(RunCommit commit, String type) {
		return commit.journal().stream().anyMatch(entry -> entry.payload().type().equals(type));
	}

	private record Result(String text, Usage usage, Object stateValue) {
	}

	private static final class Fixture {

		final MemoryRunStore memory = new MemoryRunStore();

		final RunStore store = mock(RunStore.class, delegatesTo(memory));

		final io.github.agentic.ai.workflow.graph.checkpoint.BaseCheckpointSaver legacySaver = mock(
				io.github.agentic.ai.workflow.graph.checkpoint.BaseCheckpointSaver.class);

		final TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "user"))
			.runId("run")
			.correlationId("trace")
			.build();

		final RunSpec spec = RunSpec.from(trusted, "app", "session", "submission",
				DefinitionReference.of(DefinitionKind.AGENT, "react", "1"), new RunPayload(1, "input", "{}"));

		final RunLease lease;

		final AtomicInteger modelCalls = new AtomicInteger();

		final AtomicInteger planningCalls = new AtomicInteger();

		final AtomicInteger transforms = new AtomicInteger();

		final AtomicInteger streamCalls = new AtomicInteger();

		final AtomicInteger deltas = new AtomicInteger();

		final List<ToolTaskIdentity> tasks = Collections.synchronizedList(new ArrayList<>());

		final List<String> arguments = Collections.synchronizedList(new ArrayList<>());

		final List<String> deliveredAssistantMessages = new ArrayList<>();

		final List<ChatMessage> toolMessages = new ArrayList<>();

		Predicate<RunCommit> failBefore = commit -> false;

		Predicate<RunCommit> failAfter = commit -> false;

		java.util.function.Consumer<RunCommit> afterCommit = commit -> {
		};

		ModelTurnAttemptPolicy policy = ModelTurnAttemptPolicy.DEFAULT;

		String catalogVersion = "1";

		boolean dynamic;

		boolean streaming;

		boolean replayable = true;

		boolean direct;

		boolean parallel;

		boolean transformResponse;

		boolean streamFailure;

		String requestRevision = "original";

		AgentExecutionContext activeContext;

		ExecutionGuard activeGuard;

		Fixture() {
			memory.submit(spec, trusted);
			lease = memory.acquire(spec.namespace(), spec.runId(), UUID.randomUUID(), Duration.ofMinutes(5));
			doAnswer(invocation -> {
				RunCommit commit = invocation.getArgument(3);
				if (failBefore.test(commit)) {
					throw new IllegalStateException("injected storage failure");
				}
				RunSnapshot result = memory.commit(invocation.getArgument(0), invocation.getArgument(1),
						invocation.getArgument(2), commit);
				afterCommit.accept(commit);
				if (failAfter.test(commit)) {
					throw new IllegalStateException("injected storage failure after commit");
				}
				return result;
			}).when(store).commit(any(), any(), any(), any());
		}

		RunSnapshot snapshot() {
			return memory.find(spec.namespace(), spec.runId()).orElseThrow();
		}

		AgentExecutionContext context() {
			return new AgentExecutionContext(trusted, Clock.systemUTC());
		}

		ModelTurnBindings bindings(ChatClient client, ToolRegistry tools) {
			return new ModelTurnBindings("model", "1", "immutable-test-config", client, "catalog", catalogVersion,
					tools, "deterministic-pipeline-v1", replayable ? Set.of("lookup") : Set.of());
		}

		Result run(Engine engine) throws Exception {
			return run(engine, List.of(), List.of());
		}

		Result run(Engine engine, List<Tool> additionalTools, List<ToolCall> additionalCalls) throws Exception {
			ChatClient client = new ScriptedClient(this, additionalCalls);
			List<Tool> tools = new ArrayList<>();
			tools.add(new LookupTool(this));
			tools.addAll(additionalTools);
			ToolRegistry registry = new ToolRegistry(tools);
			try (AgentExecutionContext context = context()) {
				activeContext = context;
				activeGuard = context.control();
				DurableSession session = DurableSession.reference(
						new RunCommitCoordinator(store, snapshot(), () -> lease, context.control()),
						bindings(client, registry), policy);
				ModelInterceptor interceptor = new ModelInterceptor() {
					@Override
					public String getName() {
						return "deterministic-test";
					}

					@Override
					public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
						ModelRequest.Builder prepared = ModelRequest.builder(request)
							.systemMessage(ChatMessage.system(requestRevision));
						if (dynamic) {
							prepared.toolRegistry(new ToolRegistry(List.of())).dynamicToolRegistry(registry);
						}
						ModelRequest actual = prepared.build();
						ModelResponse result = next.call(actual);
						transforms.incrementAndGet();
						if (transformResponse) {
							ChatResponse response = result.getChatResponse();
							return ModelResponse.of(new ChatResponse(response.id(), response.model(),
									List.of(ChatMessage.assistant(response.messages().get(0).text() + "-reviewed",
											response.toolCalls())),
									response.toolCalls(), response.finishReason(), response.usage(),
									response.metadata()));
						}
						return result;
					}
				};
				if (engine == Engine.GRAPH) {
					ReactAgent agent = ReactAgent.builder()
						.name("durable-test")
						.chatClient(client)
						.saver(legacySaver)
						.toolRegistry(dynamic ? new ToolRegistry(List.of()) : registry)
						.interceptors(interceptor)
						.parallelToolExecution(parallel)
						.build();
					RunnableConfig config = RunnableConfig.builder()
						.threadId("session")
						.trustedExecutionContext(trusted)
						.executionGuard(activeGuard)
						.addMetadata("_stream_", streaming)
						.build();
					List<NodeOutput> outputs = agent.stream("question", config, session).doOnNext(output -> {
						List<ChatMessage> messages = (List<ChatMessage>) output.state()
							.value("messages")
							.orElse(List.of());
						messages.stream()
							.filter(message -> message.role() == ChatRole.ASSISTANT)
							.forEach(this::observeMessage);
						if (output instanceof io.github.agentic.ai.workflow.graph.streaming.StreamingOutput<?> streamed
								&& streamed
									.getOutputType() == io.github.agentic.ai.workflow.graph.streaming.OutputType.AGENT_MODEL_STREAMING) {
							deltas.incrementAndGet();
						}
					}).collectList().block(Duration.ofSeconds(10));
					NodeOutput last = outputs.get(outputs.size() - 1);
					List<ChatMessage> messages = (List<ChatMessage>) last.state().value("messages").orElseThrow();
					return new Result(messages.get(messages.size() - 1).text(), last.tokenUsage(),
							last.state().value("lastQuery").orElse(null));
				}
				var agent = io.github.agentic.ai.agent.react.ReActAgent.builder(client)
					.tools(dynamic ? new ToolRegistry(List.of()) : registry)
					.interceptors(List.of(interceptor))
					.streamModel(streaming)
					.build();
				List<AgentEvent> events = JdkFlowAdapter
					.flowPublisherToFlux(agent.stream(AgentRequest.messages(List.of(ChatMessage.user("question"))),
							context, session))
					.doOnNext(event -> {
						if (event instanceof AgentEvent.Message message
								&& message.message().role() == ChatRole.ASSISTANT) {
							observeMessage(message.message());
						}
						if (event instanceof AgentEvent.MessageDelta) {
							deltas.incrementAndGet();
						}
					})
					.collectList()
					.block(Duration.ofSeconds(10));
				AgentOutcome outcome = ((AgentEvent.Finished) events.get(events.size() - 1)).outcome();
				return new Result(outcome.messages().get(outcome.messages().size() - 1).text(), outcome.usage(),
						outcome.outputs().get("lastQuery"));
			}
		}

		void observeMessage(ChatMessage message) {
			assertThat(snapshot().journal().values())
				.anyMatch(entry -> entry.payload().type().equals(ModelTurnCodec.RESPONSE_TYPE));
			deliveredAssistantMessages.add(message.text());
		}

	}

	private record LookupTool(Fixture fixture) implements Tool, ToolContextPatchProvider {
		@Override
		public ToolDefinition definition() {
			return new ToolDefinition("lookup", "lookup", "{\"type\":\"object\"}");
		}

		@Override
		public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
			ToolTaskIdentity identity = (ToolTaskIdentity) context.attributes()
				.get(DurableSession.TASK_IDENTITY_ATTRIBUTE);
			assertThat(identity).isNotNull();
			assertThat(fixture.snapshot().journal()).containsKey(identity.entryId());
			fixture.tasks.add(identity);
			fixture.arguments.add(call.arguments());
			Object configuration = context.attributes()
				.get(io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY);
			if (configuration instanceof RunnableConfig config) {
				assertThat(config.executionGuard()).contains(fixture.activeGuard);
				Map<String, Object> updates = (Map<String, Object>) context.attributes()
					.get(io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY);
				updates.putAll(contextPatch(call, ToolResult.text("tool-result")));
			}
			return CompletableFuture.completedFuture(ToolResult.text("tool-result"));
		}

		@Override
		public Map<String, Object> contextPatch(ToolCall call, ToolResult result) {
			return Map.of("lastQuery", call.arguments().contains("2") ? 2 : 1);
		}

		@Override
		public boolean returnDirect() {
			return fixture.direct;
		}
	}

	private record ScriptedClient(Fixture fixture, List<ToolCall> additionalCalls) implements ChatClient {

		private ScriptedClient(Fixture fixture) {
			this(fixture, List.of());
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			int invocation = fixture.modelCalls.incrementAndGet();
			List<ChatMessage> toolMessages = request.messages().stream().filter(message -> message.role() == ChatRole.TOOL)
				.toList();
			fixture.toolMessages.addAll(toolMessages);
			boolean planned = !toolMessages.isEmpty();
			List<ToolCall> calls;
			if (planned) {
				calls = List.of();
			}
			else {
				fixture.planningCalls.incrementAndGet();
				calls = new ArrayList<>();
				calls.add(new ToolCall("provider-" + invocation + "-a", "lookup", "{ \"q\": 1 }"));
				calls.add(new ToolCall("provider-" + invocation + "-b", "lookup", "{\"q\":2}"));
				calls.addAll(additionalCalls);
			}
			ChatMessage message = ChatMessage.assistant(planned ? "done" : "checking", calls);
			return CompletableFuture
				.completedFuture(new ChatResponse("response-" + invocation, "model", List.of(message), calls,
						planned ? FinishReason.STOP : FinishReason.TOOL_CALLS, new Usage(2L, 1L, 3L), Map.of()));
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			return JdkFlowAdapter.publisherToFlowPublisher(Flux.defer(() -> {
				fixture.streamCalls.incrementAndGet();
				ChatResponse response = call(request).toCompletableFuture().join();
				Flux<ChatEvent> events = Flux.just(ChatEvent.textDelta(response.messages().get(0).text()),
						ChatEvent.response(response));
				return fixture.streamFailure
						? events.concatWith(Flux.error(new IllegalStateException("stream failed after response")))
						: events;
			}));
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return new ChatClientCapabilities(true, true, false, false, true, true);
		}
	}

}
