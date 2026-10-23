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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Independent workflow executor. It interprets the closed workflow DSL directly and
 * never creates or references StateGraph, CompiledGraph or Loop runtime classes.
 */
public final class WorkflowAgent implements Agent {

	private final WorkflowDefinition definition;

	private final Function<String, Agent> agentResolver;

	private final ToolRegistry tools;

	private final WorkflowRegistry registry;

	private final ObjectMapper mapper;

	private final Map<String, StoredContinuation> continuations = new ConcurrentHashMap<>();

	private WorkflowAgent(Builder builder) {
		this.definition = Objects.requireNonNull(builder.definition, "definition must not be null");
		this.definition.validate();
		this.agentResolver = Objects.requireNonNull(builder.agentResolver, "agentResolver must not be null");
		this.tools = Objects.requireNonNull(builder.tools, "tools must not be null");
		this.registry = Objects.requireNonNull(builder.registry, "registry must not be null");
		this.mapper = Objects.requireNonNull(builder.mapper, "mapper must not be null");
	}

	public static Builder builder(WorkflowDefinition definition) {
		return new Builder(definition);
	}

	@Override
	public Flow.Publisher<AgentEvent> stream(AgentRequest request, AgentExecutionContext context) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(context, "context must not be null");
		return subscriber -> new Subscription(request, context, subscriber).subscribe();
	}

	public WorkflowDefinition definition() {
		return definition;
	}

	public static final class Builder {

		private final WorkflowDefinition definition;

		private Function<String, Agent> agentResolver = reference -> {
			throw new IllegalArgumentException("No agent is registered for reference: " + reference);
		};

		private ToolRegistry tools = new ToolRegistry(List.of());

		private WorkflowRegistry registry = new WorkflowRegistry();

		private ObjectMapper mapper = new ObjectMapper();

		private Builder(WorkflowDefinition definition) {
			this.definition = Objects.requireNonNull(definition, "definition must not be null");
		}

		public Builder agentResolver(Function<String, Agent> agentResolver) {
			this.agentResolver = Objects.requireNonNull(agentResolver, "agentResolver must not be null");
			return this;
		}

		public Builder agents(Map<String, Agent> agents) {
			Map<String, Agent> snapshot = Map.copyOf(agents);
			return agentResolver(reference -> {
				Agent agent = snapshot.get(reference);
				if (agent == null) {
					throw new IllegalArgumentException("No agent is registered for reference: " + reference);
				}
				return agent;
			});
		}

		public Builder tools(ToolRegistry tools) {
			this.tools = Objects.requireNonNull(tools, "tools must not be null");
			return this;
		}

		public Builder registry(WorkflowRegistry registry) {
			this.registry = Objects.requireNonNull(registry, "registry must not be null");
			return this;
		}

		public Builder objectMapper(ObjectMapper mapper) {
			this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
			return this;
		}

		public WorkflowAgent build() {
			return new WorkflowAgent(this);
		}

	}

	private final class Subscription implements Flow.Subscription {

		private final AgentRequest request;

		private final AgentExecutionContext context;

		private final Flow.Subscriber<? super AgentEvent> subscriber;

		private boolean started;

		private boolean cancelled;

		private Subscription(AgentRequest request, AgentExecutionContext context,
				Flow.Subscriber<? super AgentEvent> subscriber) {
			this.request = request;
			this.context = context;
			this.subscriber = Objects.requireNonNull(subscriber, "subscriber must not be null");
		}

		private void subscribe() {
			subscriber.onSubscribe(this);
		}

		@Override
		public synchronized void request(long demand) {
			if (demand <= 0) {
				if (!started) {
					started = true;
					subscriber.onError(new IllegalArgumentException("demand must be positive"));
				}
				return;
			}
			if (started || cancelled) {
				return;
			}
			started = true;
			Thread worker = new Thread(this::runSafely, "argi-workflow-agent");
			worker.setDaemon(true);
			worker.start();
		}

		@Override
		public synchronized void cancel() {
			if (!cancelled) {
				cancelled = true;
				context.control().cancel();
			}
		}

		private void runSafely() {
			try {
				ExecutionResult result = run();
				if (!cancelled) {
					if (result.suspension != null) {
						String token = register(result.continuation);
						AgentOutcome outcome = new AgentOutcome(AgentOutcome.Status.SUSPENDED, result.data.messages,
								result.data.state, result.data.usage(),
								new AgentOutcome.Suspension(token, result.suspension.prompts()));
						emit(new AgentEvent.Finished(outcome));
					}
					else {
						AgentOutcome outcome = AgentOutcome.completed(result.data.messages, result.data.state,
								result.data.usage());
						emit(new AgentEvent.Finished(outcome));
					}
					subscriber.onComplete();
				}
			}
			catch (Throwable error) {
				if (!cancelled) {
					subscriber.onError(error);
				}
			}
		}

		private ExecutionResult run() {
			context.control().assertActive();
			if (request.resumeToken() != null) {
				StoredContinuation stored = continuations.remove(request.resumeToken());
				if (stored == null || !stored.matches(context)) {
					throw new IllegalArgumentException("Unknown or unauthorized workflow resume token");
				}
				return stored.continuation.resume(request, context);
			}
			RunData data = new RunData(request);
			return executeSteps(definition.steps(), 0, data, context);
		}

		private String register(Continuation continuation) {
			String token = definition.id() + ":" + definition.version() + ":" + UUID.randomUUID();
			continuations.put(token, new StoredContinuation(context, continuation));
			return token;
		}

		private void emit(AgentEvent event) {
			if (!cancelled) {
				subscriber.onNext(event);
			}
		}
	}

	private ExecutionResult executeSteps(List<WorkflowStep> steps, int index, RunData data,
		AgentExecutionContext context) {
		context.control().assertActive();
		if (index == steps.size()) {
			return ExecutionResult.completed(data);
		}
		ExecutionResult result = executeStep(steps.get(index), data, context);
		if (result.suspension == null) {
			return executeSteps(steps, index + 1, data, context);
		}
		return result.continueWith((request, resumeContext) -> {
			ExecutionResult resumed = result.continuation.resume(request, resumeContext);
			return resumed.suspension == null ? executeSteps(steps, index + 1, data, resumeContext) : resumed;
		});
	}

	private ExecutionResult executeStep(WorkflowStep step, RunData data, AgentExecutionContext context) {
		context.control().assertActive();
		if (step instanceof WorkflowStep.Agent agent) {
			return executeWithRetry(agent.retry(), () -> executeAgent(agent, data, context), context);
		}
		if (step instanceof WorkflowStep.Tool tool) {
			return executeWithRetry(tool.retry(), () -> executeTool(tool, data, context), context);
		}
		if (step instanceof WorkflowStep.Sequence sequence) {
			return executeSteps(sequence.steps(), 0, data, context);
		}
		if (step instanceof WorkflowStep.Condition condition) {
			WorkflowStep selected = truthy(resolve(condition.condition(), data)) ? condition.whenTrue() : condition.whenFalse();
			return selected == null ? ExecutionResult.completed(data) : executeStep(selected, data, context);
		}
		if (step instanceof WorkflowStep.Parallel parallel) {
			return executeParallel(parallel, data, context);
		}
		if (step instanceof WorkflowStep.Loop loop) {
			for (int iteration = 0; iteration < loop.maxIterations(); iteration++) {
				context.control().assertActive();
				if (!truthy(resolve(loop.condition(), data))) {
					return ExecutionResult.completed(data);
				}
				ExecutionResult result = executeStep(loop.body(), data, context);
				if (result.suspension != null) {
					int nextIteration = iteration + 1;
					return result.continueWith((request, resumeContext) -> {
						ExecutionResult resumed = result.continuation.resume(request, resumeContext);
						if (resumed.suspension != null) {
							return resumed;
						}
						for (int next = nextIteration; next < loop.maxIterations(); next++) {
							if (!truthy(resolve(loop.condition(), data))) {
								return ExecutionResult.completed(data);
							}
							ExecutionResult nested = executeStep(loop.body(), data, resumeContext);
							if (nested.suspension != null) {
								return nested;
							}
						}
						return ExecutionResult.completed(data);
					});
				}
			}
			throw new WorkflowExecutionException("Workflow loop exceeded maxIterations: " + loop.id());
		}
		if (step instanceof WorkflowStep.Subflow subflow) {
			WorkflowDefinition nested = registry.get(subflow.workflowId(), subflow.version());
			return executeSteps(nested.steps(), 0, data, context);
		}
		throw new WorkflowExecutionException("Unsupported workflow step: " + step.id());
	}

	private ExecutionResult executeAgent(WorkflowStep.Agent step, RunData data, AgentExecutionContext context) {
		Agent agent = Objects.requireNonNull(agentResolver.apply(step.agentRef()),
				"agent resolver returned no agent for " + step.agentRef());
		Map<String, Object> inputs = resolveMap(step.inputs(), data);
		AgentOutcome outcome = await(agent.invoke(new AgentRequest(data.messages, inputs, null), context));
		data.messages.addAll(outcome.messages());
		data.addUsage(outcome.usage());
		if (outcome.status() == AgentOutcome.Status.SUSPENDED) {
			return ExecutionResult.suspended(data, outcome.suspension().prompts(), (request, resumeContext) -> {
				AgentOutcome resumed = await(agent.invoke(new AgentRequest(request.messages(), request.inputs(),
						outcome.suspension().resumeToken()), resumeContext));
				data.messages.addAll(resumed.messages());
				data.addUsage(resumed.usage());
				if (resumed.status() == AgentOutcome.Status.SUSPENDED) {
					return ExecutionResult.suspended(data, resumed.suspension().prompts(), (next, nextContext) -> {
						AgentOutcome finalOutcome = await(agent.invoke(new AgentRequest(next.messages(), next.inputs(),
								resumed.suspension().resumeToken()), nextContext));
						data.messages.addAll(finalOutcome.messages());
						data.addUsage(finalOutcome.usage());
						return finalOutcome.status() == AgentOutcome.Status.SUSPENDED
								? ExecutionResult.suspended(data, finalOutcome.suspension().prompts(), (again, againContext) ->
										ExecutionResult.completed(data)) : applyAgentOutput(step, finalOutcome, data);
					});
				}
				return applyAgentOutput(step, resumed, data);
			});
		}
		return applyAgentOutput(step, outcome, data);
	}

	private ExecutionResult applyAgentOutput(WorkflowStep.Agent step, AgentOutcome outcome, RunData data) {
		Map<String, Object> output = outcome.outputs();
		data.stepOutputs.put(step.id(), output);
		if (step.outputKey() != null) {
			data.state.put(step.outputKey(), output);
		}
		return ExecutionResult.completed(data);
	}

	private ExecutionResult executeTool(WorkflowStep.Tool step, RunData data, AgentExecutionContext context) {
		Tool tool = tools.find(step.toolName()).orElseThrow(() ->
				new WorkflowExecutionException("Unknown workflow tool: " + step.toolName()));
		Map<String, Object> arguments = resolveMap(step.arguments(), data);
		String json;
		try {
			json = mapper.writeValueAsString(arguments);
		}
		catch (Exception exception) {
			throw new WorkflowExecutionException("Workflow tool arguments are not serializable: " + step.id(), exception);
		}
		ToolResult result = await(tool.execute(new ToolCall(step.id() + ":" + UUID.randomUUID(), step.toolName(), json),
				new ToolContext(data.state)).toCompletableFuture());
		if (result.error()) {
			throw new WorkflowExecutionException("Workflow tool failed: " + step.toolName());
		}
		Object output = result.content().isEmpty() ? null : result.content().get(0).text();
		data.stepOutputs.put(step.id(), output);
		if (step.outputKey() != null) {
			data.state.put(step.outputKey(), output);
		}
		return ExecutionResult.completed(data);
	}

	private ExecutionResult executeParallel(WorkflowStep.Parallel step, RunData data, AgentExecutionContext context) {
		int baseMessageCount = data.messages.size();
		Semaphore permits = new Semaphore(step.maxConcurrency());
		List<CompletableFuture<RunData>> futures = step.branches().stream()
			.map(branch -> CompletableFuture.supplyAsync(() -> {
				try {
					permits.acquire();
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new WorkflowExecutionException("Parallel workflow admission was interrupted", interrupted);
				}
				RunData branchData = data.copy();
				try {
					ExecutionResult result = executeStep(branch, branchData, context);
					if (result.suspension != null) {
						throw new WorkflowExecutionException("Parallel workflow suspension requires durable coordination");
					}
					return branchData;
				}
				finally {
					permits.release();
				}
			}))
			.toList();
		for (CompletableFuture<RunData> future : futures) {
			RunData branch = await(future);
			data.state.putAll(branch.state);
			data.stepOutputs.putAll(branch.stepOutputs);
			data.messages.addAll(branch.messages.subList(baseMessageCount, branch.messages.size()));
			data.addUsage(branch.usage());
		}
		return ExecutionResult.completed(data);
	}

	private ExecutionResult executeWithRetry(WorkflowStep.RetryPolicy policy, Operation operation,
		AgentExecutionContext context) {
		RuntimeException failure = null;
		for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
			try {
				return operation.run();
			}
			catch (RuntimeException exception) {
				failure = exception;
				if (attempt < policy.maxAttempts() && !policy.backoff().isZero()) {
					try {
						Thread.sleep(policy.backoff().toMillis());
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new WorkflowExecutionException("Workflow retry was interrupted", interrupted);
					}
				}
				context.control().assertActive();
			}
		}
		throw new WorkflowExecutionException("Workflow step failed after " + policy.maxAttempts() + " attempts", failure);
	}

	private Map<String, Object> resolveMap(Map<String, WorkflowValue> expressions, RunData data) {
		Map<String, Object> result = new LinkedHashMap<>();
		expressions.forEach((key, value) -> result.put(key, resolve(value, data)));
		return result;
	}

	private Object resolve(WorkflowValue value, RunData data) {
		if (value instanceof WorkflowValue.Literal literal) {
			return literal.value();
		}
		if (value instanceof WorkflowValue.Input input) {
			return path(data.inputs, input.path());
		}
		if (value instanceof WorkflowValue.State state) {
			return path(data.state, state.path());
		}
		if (value instanceof WorkflowValue.StepOutput output) {
			return path(data.stepOutputs.get(output.stepId()), output.path());
		}
		if (value instanceof WorkflowValue.Compare compare) {
			return compare(compare.operator(), resolve(compare.left(), data), resolve(compare.right(), data));
		}
		if (value instanceof WorkflowValue.All all) {
			return all.values().stream().allMatch(item -> truthy(resolve(item, data)));
		}
		if (value instanceof WorkflowValue.Any any) {
			return any.values().stream().anyMatch(item -> truthy(resolve(item, data)));
		}
		if (value instanceof WorkflowValue.Not not) {
			return !truthy(resolve(not.value(), data));
		}
		throw new WorkflowExecutionException("Unsupported workflow expression");
	}

	private static Object path(Object value, String path) {
		Object current = value;
		for (String segment : path.split("\\.")) {
			if (current instanceof Map<?, ?> map) {
				current = map.get(segment);
			}
			else if (current instanceof List<?> list && segment.matches("\\d+")) {
				int index = Integer.parseInt(segment);
				current = index < list.size() ? list.get(index) : null;
			}
			else {
				return null;
			}
		}
		return current;
	}

	private static boolean truthy(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0;
		}
		return value != null && (!value.toString().isBlank()) && !"false".equalsIgnoreCase(value.toString());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static boolean compare(WorkflowValue.Operator operator, Object left, Object right) {
		if (operator == WorkflowValue.Operator.EQUALS) {
			return Objects.equals(left, right);
		}
		if (operator == WorkflowValue.Operator.NOT_EQUALS) {
			return !Objects.equals(left, right);
		}
		if (left == null || right == null) {
			return false;
		}
		int result;
		if (left instanceof Number && right instanceof Number) {
			result = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
		}
		else if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
			result = comparable.compareTo(right);
		}
		else {
			throw new WorkflowExecutionException("Workflow comparison operands are not comparable");
		}
		return switch (operator) {
			case GREATER_THAN -> result > 0;
			case GREATER_THAN_OR_EQUAL -> result >= 0;
			case LESS_THAN -> result < 0;
			case LESS_THAN_OR_EQUAL -> result <= 0;
			default -> throw new WorkflowExecutionException("Unsupported comparison operator: " + operator);
		};
	}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get();
		}
		catch (InterruptedException interrupted) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new WorkflowExecutionException("Workflow execution interrupted", interrupted);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new CompletionException(cause);
		}
	}

	private interface Operation {
		ExecutionResult run();
	}

	@FunctionalInterface
	private interface Continuation {
		ExecutionResult resume(AgentRequest request, AgentExecutionContext context);
	}

	private record SuspensionData(Map<String, Object> prompts) {
	}

	private record ExecutionResult(RunData data, SuspensionData suspension, Continuation continuation) {
		private static ExecutionResult completed(RunData data) {
			return new ExecutionResult(data, null, null);
		}

		private static ExecutionResult suspended(RunData data, Map<String, Object> prompts, Continuation continuation) {
			return new ExecutionResult(data, new SuspensionData(Map.copyOf(prompts)), continuation);
		}

		private ExecutionResult continueWith(Continuation next) {
			return new ExecutionResult(null, suspension, (request, context) -> {
				ExecutionResult result = continuation.resume(request, context);
				return result.suspension == null ? next.resume(request, context) : result;
			});
		}
	}

	private static final class RunData {

		private final Map<String, Object> inputs;

		private final Map<String, Object> state;

		private final Map<String, Object> stepOutputs;

		private final List<ChatMessage> messages;

		private Long inputTokens = 0L;

		private Long outputTokens = 0L;

		private Long totalTokens = 0L;

		private RunData(AgentRequest request) {
			this.inputs = new LinkedHashMap<>(request.inputs());
			this.state = new LinkedHashMap<>(request.inputs());
			this.stepOutputs = new LinkedHashMap<>();
			this.messages = new ArrayList<>(request.messages());
		}

		private RunData(RunData source) {
			this.inputs = new LinkedHashMap<>(source.inputs);
			this.state = new LinkedHashMap<>(source.state);
			this.stepOutputs = new LinkedHashMap<>(source.stepOutputs);
			this.messages = new ArrayList<>(source.messages);
			this.inputTokens = source.inputTokens;
			this.outputTokens = source.outputTokens;
			this.totalTokens = source.totalTokens;
		}

		private RunData copy() {
			return new RunData(this);
		}

		private void addUsage(Usage usage) {
			inputTokens = add(inputTokens, usage.inputTokens());
			outputTokens = add(outputTokens, usage.outputTokens());
			totalTokens = add(totalTokens, usage.totalTokens());
		}

		private Usage usage() {
			return new Usage(inputTokens, outputTokens, totalTokens);
		}

		private static Long add(Long left, Long right) {
			return left == null || right == null ? null : left + right;
		}
	}

	private record StoredContinuation(AgentExecutionContext context, Continuation continuation) {
		private boolean matches(AgentExecutionContext candidate) {
			return context.trusted().tenant().equals(candidate.trusted().tenant())
					&& context.trusted().principal().equals(candidate.trusted().principal())
					&& context.trusted().runId().equals(candidate.trusted().runId());
		}
	}

}
