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
package io.github.agentic.ai.agent.orchestration.tools.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.agentic.ai.adapter.springai.SpringAiChatClientAdapter;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.agent.orchestration.ReactAgent;
import io.github.agentic.ai.agent.orchestration.hook.HookPosition;
import io.github.agentic.ai.agent.orchestration.hook.HookPositions;
import io.github.agentic.ai.agent.orchestration.hook.ModelHook;
import io.github.agentic.ai.agent.orchestration.tools.ToolContextConstants;
import io.github.agentic.ai.workflow.graph.checkpoint.savers.MemorySaver;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceKeys;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceOperation;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TaskTool/TaskOutputTool cross-agent trace correlation tests")
class TaskToolTraceCorrelationTests {

	private final RecordingHandler handler = new RecordingHandler();

	private AgentTracer enabledTracer() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(this.handler);
		return new AgentTracer(registry);
	}

	private ReactAgent subAgent(String name, ChatModel chatModel, ModelHook hook) {
		var builder = ReactAgent.builder()
			.name(name)
			.chatClient(new SpringAiChatClientAdapter(chatModel))
			.saver(new MemorySaver());
		if (hook != null) {
			builder.hooks(List.of(hook));
		}
		return builder.build();
	}

	private RunnableConfig parentConfig() {
		return RunnableConfig.builder()
			.threadId("parent-thread")
			.addMetadata("_AGENT_", "parent_agent")
			.trustedExecutionContext(TrustedExecutionContext.builder()
				.tenant(new TenantReference("example-iam", "tenant-a"))
				.principal(new PrincipalReference("example-iam", "principal-a"))
				.runId("run-1")
				.correlationId("correlation-a")
				.budget(new ExecutionBudget(Map.of("tokens", 100L)))
				.delegationScope(new DelegationScope(Set.of("agents:delegate")))
				.build())
			.build();
	}

	private ToolContext toolContext(RunnableConfig config) {
		return new ToolContext(Map.of(ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY, config));
	}

	@Test
	@DisplayName("synchronous delegation emits a correlated span and derives the child config")
	void syncDelegationEmitsSpanAndPropagatesConfig() {
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("child response"), captureHook);
		TaskTool tool = new TaskTool(Map.of("child_agent", child), new DefaultTaskRepository(), enabledTracer(), null);

		String result = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", null),
				toolContext(parentConfig()));

		assertThat(result).isEqualTo("child response");
		assertThat(captureHook.capturedConfig()).isNotNull();
		assertThat(captureHook.capturedConfig().threadId()).contains("parent-thread_child_agent");
		assertThat(captureHook.capturedConfig().trustedExecutionContext().orElseThrow().tenant().id())
			.isEqualTo("tenant-a");

		assertThat(this.handler.stopped).hasSize(1);
		Observation.Context span = this.handler.stopped.get(0);
		assertThat(span.getName()).isEqualTo("argi.agent.delegation");
		assertThat(span.getHighCardinalityKeyValue(AgentTraceKeys.RUN_ID).getValue()).isEqualTo("run-1");
		assertThat(span.getHighCardinalityKeyValue(AgentTraceKeys.TENANT).getValue()).isEqualTo("tenant-a");
		assertThat(span.getHighCardinalityKeyValue(AgentTraceKeys.DELEGATION_ID).getValue()).isNotBlank();
		assertThat(span.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("true");
		assertThat(String.valueOf(span.getAllKeyValues())).doesNotContain("prompt");
	}

	@Test
	@DisplayName("the attempt id is the graph execution id from the tool state")
	void attemptIdComesFromGraphExecutionId() {
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("child response"), captureHook);
		TaskTool tool = new TaskTool(Map.of("child_agent", child), new DefaultTaskRepository(), enabledTracer(), null);
		OverAllState state = new OverAllState(
				Map.of("_graph_execution_id_", "exec-1"));

		tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", null),
				new ToolContext(Map.of(ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY, parentConfig(),
						ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state)));

		assertThat(this.handler.stopped.get(0).getHighCardinalityKeyValue(AgentTraceKeys.ATTEMPT_ID).getValue())
			.isEqualTo("exec-1");
	}

	@Test
	@DisplayName("a disabled tracer preserves the historical behavior")
	void disabledTracerPreservesHistoricalBehavior() {
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("child response"), captureHook);
		TaskTool tool = new TaskTool(Map.of("child_agent", child), new DefaultTaskRepository());

		String result = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", null),
				toolContext(parentConfig()));

		assertThat(result).isEqualTo("child response");
		// ReactAgent.call(prompt) internally builds its own config; the historical
		// behavior contract is that the tool did NOT derive one from the parent.
		assertThat(captureHook.capturedConfig()).as("child must not receive a derived config").isNotNull();
		assertThat(captureHook.capturedConfig().threadId())
			.as("thread id must not be derived from the parent thread")
			.isEmpty();
		assertThat(this.handler.stopped).isEmpty();
	}

	@Test
	@DisplayName("background delegation and the receive span link through the delegation id")
	void backgroundDelegationAndReceiveLinkThroughDelegationId() throws Exception {
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("child response"), captureHook);
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = TaskTraceCorrelation.empty();
		DefaultTaskRepository repository = new DefaultTaskRepository();
		TaskTool tool = new TaskTool(Map.of("child_agent", child), repository, tracer, correlation);
		TaskOutputTool output = new TaskOutputTool(repository, tracer, correlation);

		String launchResult = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", true),
				toolContext(parentConfig()));
		Matcher matcher = Pattern.compile("task_id: (task_[a-f0-9\\-]+)").matcher(launchResult);
		assertThat(matcher.find()).isTrue();
		String taskId = matcher.group(1);

		String outputResult = output.apply(new TaskOutputTool.Request(taskId, true, 10000L),
				toolContext(parentConfig()));

		assertThat(outputResult).contains("Status: Completed").contains("child response");

		Observation.Context delegation = this.handler.stopped.stream()
			.filter(span -> span.getName().equals(AgentTraceOperation.AGENT_DELEGATION.operationName()))
			.findFirst()
			.orElseThrow();
		Observation.Context receive = this.handler.stopped.stream()
			.filter(span -> span.getName().equals(AgentTraceOperation.TASK_RECEIVE.operationName()))
			.findFirst()
			.orElseThrow();
		String delegationId = delegation.getHighCardinalityKeyValue(AgentTraceKeys.DELEGATION_ID).getValue();
		assertThat(delegationId).isNotBlank();
		assertThat(delegation.getHighCardinalityKeyValue(AgentTraceKeys.TASK_ID).getValue()).isEqualTo(taskId);
		assertThat(delegation.getHighCardinalityKeyValue(AgentTraceKeys.RUN_ID).getValue()).isEqualTo("run-1");
		assertThat(receive.getLowCardinalityKeyValue(AgentTraceKeys.ROLE).getValue()).isEqualTo("consumer");
		assertThat(receive.getHighCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID).getValue())
			.isEqualTo(delegationId);
		assertThat(receive.getHighCardinalityKeyValue(AgentTraceKeys.TASK_ID).getValue()).isEqualTo(taskId);
		assertThat(receive.getLowCardinalityKeyValue(AgentTraceKeys.OUTCOME).getValue()).isEqualTo("completed");
	}

	@Test
	@DisplayName("a timeout keeps the correlation and records the timeout outcome")
	void timeoutKeepsCorrelationAndRecordsOutcome() throws Exception {
		ReactAgent child = subAgent("child_agent", new SlowResponseChatModel(2000), null);
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = TaskTraceCorrelation.empty();
		DefaultTaskRepository repository = new DefaultTaskRepository();
		TaskTool tool = new TaskTool(Map.of("child_agent", child), repository, tracer, correlation);
		TaskOutputTool output = new TaskOutputTool(repository, tracer, correlation);

		String launchResult = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", true),
				toolContext(parentConfig()));
		Matcher matcher = Pattern.compile("task_id: (task_[a-f0-9\\-]+)").matcher(launchResult);
		assertThat(matcher.find()).isTrue();

		String outputResult = output.apply(new TaskOutputTool.Request(matcher.group(1), true, 100L),
				toolContext(parentConfig()));

		assertThat(outputResult).contains("Task still running...");
		Observation.Context receive = this.handler.stopped.stream()
			.filter(span -> span.getName().equals(AgentTraceOperation.TASK_RECEIVE.operationName()))
			.findFirst()
			.orElseThrow();
		assertThat(receive.getLowCardinalityKeyValue(AgentTraceKeys.OUTCOME).getValue()).isEqualTo("timeout");
		assertThat(receive.getHighCardinalityKeyValue(AgentTraceKeys.RUN_ID).getValue()).isEqualTo("run-1");
	}

	@Test
	@DisplayName("sub-agent failures are recorded with redacted content")
	void failingSubAgentIsRecordedWithRedactedContent() {
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("unused"), new FailingHook());
		TaskTool tool = new TaskTool(Map.of("child_agent", child), new DefaultTaskRepository(), enabledTracer(), null);

		String result = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", null),
				toolContext(parentConfig()));

		assertThat(result).isEqualTo("Error executing sub-agent: boom with password=hunter2");
		assertThat(this.handler.stopped).hasSize(1);
		Observation.Context span = this.handler.stopped.get(0);
		assertThat(span.getLowCardinalityKeyValue(AgentTraceKeys.SUCCESS).getValue()).isEqualTo("false");
		String recorded = span.getHighCardinalityKeyValue(AgentTraceKeys.ERROR).getValue();
		assertThat(recorded).doesNotContain("hunter2");
		assertThat(recorded).contains("<redacted>");
	}

	@Test
	@DisplayName("a rejected submission rolls back the recorded correlation")
	void aRejectedSubmissionRollsBackTheCorrelation() {
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("unused"), captureHook);
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = TaskTraceCorrelation.empty();
		ExecutorService stalled = mock(ExecutorService.class);
		try (BoundedTaskRepository repository = new BoundedTaskRepository(stalled, 1, 0, 1, Duration.ofMinutes(1))) {
			repository.putTask("occupy", () -> "occupy");
			TaskTool tool = new TaskTool(Map.of("child_agent", child), repository, tracer, correlation);

			assertThatThrownBy(() -> tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", true),
					toolContext(parentConfig()))).isInstanceOf(RejectedExecutionException.class);

			assertThat(correlation.retainedCount())
				.as("the submission was rejected, so no correlation may remain")
				.isZero();
			assertThat(captureHook.capturedConfig())
				.as("the rejected delegation must not have executed the sub-agent")
				.isNull();
		}
	}

	@Test
	@DisplayName("a terminal result keeps the correlation so later polls still link")
	void aTerminalResultKeepsTheLinkForSubsequentPolls() throws Exception {
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", new FixedResponseChatModel("child response"), captureHook);
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = TaskTraceCorrelation.empty();
		DefaultTaskRepository repository = new DefaultTaskRepository();
		TaskTool tool = new TaskTool(Map.of("child_agent", child), repository, tracer, correlation);
		TaskOutputTool output = new TaskOutputTool(repository, tracer, correlation);

		String launchResult = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", true),
				toolContext(parentConfig()));
		Matcher matcher = Pattern.compile("task_id: (task_[a-f0-9\\-]+)").matcher(launchResult);
		assertThat(matcher.find()).isTrue();
		String taskId = matcher.group(1);

		String firstPoll = output.apply(new TaskOutputTool.Request(taskId, true, 10000L), toolContext(parentConfig()));

		assertThat(firstPoll).contains("Status: Completed").contains("child response");
		assertThat(correlation.retainedCount())
			.as("the link must survive the terminal delivery for later polls (#370 review)")
			.isEqualTo(1);

		String secondPoll = output.apply(new TaskOutputTool.Request(taskId, false, 1L), toolContext(parentConfig()));

		assertThat(secondPoll).as("the repository result itself is untouched").contains("Status: Completed")
			.contains("child response");
		// Both receive spans linked to the same delegation id: the correlation survived.
		assertThat(this.handler.stopped).filteredOn(span -> span.getName().equals(
				AgentTraceOperation.TASK_RECEIVE.operationName())).hasSize(2)
			.allSatisfy(span -> assertThat(span.getHighCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID))
				.isNotNull());
	}

	@Test
	@DisplayName("a task finishing after the retention window keeps its link for the first poll")
	void aLongRunningTaskKeepsItsLinkUntilPolledAfterCompletion() throws Exception {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
		// The sub-agent runs for 31 minutes of clock time before completing.
		ChatModel longModel = new ChatModel() {
			public ChatResponse call(Prompt prompt) {
				clock.advance(Duration.ofMinutes(31));
				return new ChatResponse(List.of(new Generation(new AssistantMessage("late response"))));
			}

			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.just(call(prompt));
			}
		};
		ConfigCaptureHook captureHook = new ConfigCaptureHook();
		ReactAgent child = subAgent("child_agent", longModel, captureHook);
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = new TaskTraceCorrelation(clock, Duration.ofMinutes(30));
		DefaultTaskRepository repository = new DefaultTaskRepository();
		TaskTool tool = new TaskTool(Map.of("child_agent", child), repository, tracer, correlation);
		TaskOutputTool output = new TaskOutputTool(repository, tracer, correlation);

		String launchResult = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", true),
				toolContext(parentConfig()));
		Matcher matcher = Pattern.compile("task_id: (task_[a-f0-9\\-]+)").matcher(launchResult);
		assertThat(matcher.find()).isTrue();
		String taskId = matcher.group(1);

		String firstPoll = output.apply(new TaskOutputTool.Request(taskId, true, 20000L), toolContext(parentConfig()));

		assertThat(firstPoll).contains("Status: Completed").contains("late response");

		// The second poll happens after 31 minutes of clock time: without the
		// completion-time refresh the submission-anchored window has already lapsed
		// and the receive span could no longer link.
		output.apply(new TaskOutputTool.Request(taskId, false, 1L), toolContext(parentConfig()));

		List<Observation.Context> receives = this.handler.stopped.stream()
			.filter(span -> span.getName().equals(AgentTraceOperation.TASK_RECEIVE.operationName()))
			.toList();
		assertThat(receives).hasSize(2);
		assertThat(receives.get(0).getHighCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID)).isNotNull();
		assertThat(receives.get(1).getHighCardinalityKeyValue(AgentTraceKeys.LINK_DELEGATION_ID))
			.as("the completion-time refresh must keep the link alive past the submission window")
			.isNotNull();
	}

	@Test
	@DisplayName("polling an unknown task sweeps expired entries without a new submission")
	void aPollOfAnUnknownTaskSweepsAllExpiredEntriesWithoutANewSubmission() throws Exception {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = new TaskTraceCorrelation(clock, Duration.ofMinutes(30));
		DefaultTaskRepository repository = new DefaultTaskRepository();
		TaskOutputTool output = new TaskOutputTool(repository, tracer, correlation);
		correlation.record("task-stale-1", AgentTraceContext.capture(null, null).withTaskId("task-stale-1"));
		correlation.record("task-stale-2", AgentTraceContext.capture(null, null).withTaskId("task-stale-2"));
		// Completed tasks: running entries are retained and never expire.
		correlation.refresh("task-stale-1");
		correlation.refresh("task-stale-2");
		clock.advance(Duration.ofMinutes(31));

		String result = output.apply(new TaskOutputTool.Request("task-never-existed", false, 1L),
				toolContext(parentConfig()));

		assertThat(result).contains("No background task found");
		assertThat(correlation.retainedCount())
			.as("one poll must sweep every expired entry, submission or not (#370 review gap 2)")
			.isZero();
	}

	@Test
	@DisplayName("polling a removed task releases its correlation")
	void aPollOfARemovedTaskReleasesItsCorrelation() throws Exception {
		ReactAgent child = mock(ReactAgent.class);
		when(child.call(anyString())).thenReturn(ChatMessage.assistant("done"));
		AgentTracer tracer = enabledTracer();
		TaskTraceCorrelation correlation = TaskTraceCorrelation.empty();
		DefaultTaskRepository repository = new DefaultTaskRepository();
		TaskTool tool = new TaskTool(Map.of("child_agent", child), repository, tracer, correlation);
		TaskOutputTool output = new TaskOutputTool(repository, tracer, correlation);

		String launchResult = tool.apply(new TaskTool.Request("desc", "prompt", "child_agent", true),
				toolContext(parentConfig()));
		Matcher matcher = Pattern.compile("task_id: (task_[a-f0-9\\-]+)").matcher(launchResult);
		assertThat(matcher.find()).isTrue();
		String taskId = matcher.group(1);
		repository.removeTask(taskId);

		String result = output.apply(new TaskOutputTool.Request(taskId, false, 1L), toolContext(parentConfig()));

		assertThat(result).contains("No background task found");
		assertThat(correlation.retainedCount())
			.as("a task removed from the repository must not keep a stranded correlation")
			.isZero();
	}

	static final class RecordingHandler implements ObservationHandler<Observation.Context> {

		final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

		@Override
		public boolean supportsContext(Observation.Context context) {
			return true;
		}

		@Override
		public void onStop(Observation.Context context) {
			this.stopped.add(context);
		}

	}

	@HookPositions(HookPosition.BEFORE_MODEL)
	private static class ConfigCaptureHook extends ModelHook {

		private final AtomicReference<RunnableConfig> capturedConfig = new AtomicReference<>();

		@Override
		public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
			capturedConfig.set(config);
			return CompletableFuture.completedFuture(Map.of());
		}

		RunnableConfig capturedConfig() {
			return capturedConfig.get();
		}

		@Override
		public String getName() {
			return "ConfigCaptureHook";
		}

	}

	@HookPositions(HookPosition.BEFORE_MODEL)
	private static class FailingHook extends ModelHook {

		@Override
		public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
			throw new IllegalStateException("boom with password=hunter2");
		}

		@Override
		public String getName() {
			return "FailingHook";
		}

	}

	private static class FixedResponseChatModel implements ChatModel {

		private final String responseText;

		private FixedResponseChatModel(String responseText) {
			this.responseText = responseText;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage(responseText))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	private static class SlowResponseChatModel implements ChatModel {

		private final long delayMillis;

		private SlowResponseChatModel(long delayMillis) {
			this.delayMillis = delayMillis;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			try {
				Thread.sleep(this.delayMillis);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("slow response"))));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	/** Mutable clock for deterministic retention tests (the #331 metrics pattern). */
	static final class MutableClock extends java.time.Clock {

		private java.time.Instant now;

		private MutableClock(java.time.Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public java.time.ZoneOffset getZone() {
			return java.time.ZoneOffset.UTC;
		}

		@Override
		public java.time.Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public java.time.Instant instant() {
			return this.now;
		}

	}

}
