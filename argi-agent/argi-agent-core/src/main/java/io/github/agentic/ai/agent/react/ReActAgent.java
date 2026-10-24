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

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.agent.AgentResult;
import io.github.agentic.ai.agent.outcome.BusinessOutcomeMapperRegistry;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.agent.interceptor.authorization.AuthorizationDecision;
import io.github.agentic.ai.agent.interceptor.authorization.ToolAuthorizationPolicy;
import io.github.agentic.ai.agent.result.StructuredOutputFinalizer;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchPolicy;
import io.github.agentic.ai.agent.tool.patch.strict.StateIsolation;
import io.github.agentic.ai.agent.tool.patch.strict.StrictPatchAdmission;
import io.github.agentic.ai.agent.tool.patch.strict.ToolUpdateUnit;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchValidator;
import io.github.agentic.ai.agent.tool.patch.ToolContextPatchViolationException;
import io.github.agentic.ai.agent.validation.ToolArgumentValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A graph-independent ReAct loop. Each turn performs model call, tool authorization
 * and validation, tool execution, context-patch admission, then either terminates or
 * starts another model turn. It does not construct StateGraph, CompiledGraph or
 * RunnableConfig and it has no checkpoint or scheduler dependency.
 */
public final class ReActAgent implements Agent {

	private final ChatClient chatClient;

	private final ToolRegistry tools;

	private final List<ModelInterceptor> interceptors;

	private final ToolAuthorizationPolicy authorizationPolicy;

	private final boolean validateToolArguments;

	private final ToolContextPatchPolicy patchPolicy;

	private final boolean strictToolState;

	private final BusinessOutcomeMapperRegistry outcomeMappers;

	private final HumanInTheLoop humanInTheLoop;

	private final StructuredOutputFinalizer outputFinalizer;

	private final String outputSchema;

	private final int maxTurns;

	private final boolean streamModel;

	private final Function<Map<String, Object>, ToolRegistry> dynamicToolProvider;

	private ReActAgent(Builder builder) {
		this.chatClient = Objects.requireNonNull(builder.chatClient, "chatClient must not be null");
		this.tools = Objects.requireNonNull(builder.tools, "tools must not be null");
		this.interceptors = List.copyOf(builder.interceptors);
		this.authorizationPolicy = Objects.requireNonNull(builder.authorizationPolicy,
				"authorizationPolicy must not be null");
		this.validateToolArguments = builder.validateToolArguments;
		this.patchPolicy = builder.patchPolicy;
		this.strictToolState = builder.strictToolState;
		this.outcomeMappers = Objects.requireNonNull(builder.outcomeMappers, "outcomeMappers must not be null");
		this.humanInTheLoop = builder.humanInTheLoop;
		this.outputFinalizer = builder.outputFinalizer;
		this.outputSchema = builder.outputSchema;
		this.maxTurns = builder.maxTurns;
		this.streamModel = builder.streamModel;
		this.dynamicToolProvider = builder.dynamicToolProvider;
	}

	public static Builder builder(ChatClient chatClient) {
		return new Builder(chatClient);
	}

	@Override
	public Flow.Publisher<AgentEvent> stream(AgentRequest request, AgentExecutionContext context) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(context, "context must not be null");
		return subscriber -> new RunSubscription(request, context, subscriber).subscribe();
	}

	public static final class Builder {

		private final ChatClient chatClient;

		private ToolRegistry tools = new ToolRegistry(List.of());

		private List<ModelInterceptor> interceptors = List.of();

		private ToolAuthorizationPolicy authorizationPolicy = ToolAuthorizationPolicy.allowAll();

		private boolean validateToolArguments;

		private ToolContextPatchPolicy patchPolicy;

		private boolean strictToolState;

		private BusinessOutcomeMapperRegistry outcomeMappers = BusinessOutcomeMapperRegistry.empty();

		private HumanInTheLoop humanInTheLoop;

		private StructuredOutputFinalizer outputFinalizer;

		private String outputSchema;

		private int maxTurns = 16;

		private boolean streamModel;

		private Function<Map<String, Object>, ToolRegistry> dynamicToolProvider;

		private Builder(ChatClient chatClient) {
			this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		}

		public Builder tools(ToolRegistry tools) {
			this.tools = Objects.requireNonNull(tools, "tools must not be null");
			return this;
		}

		public Builder interceptors(List<? extends ModelInterceptor> interceptors) {
			this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors must not be null"));
			return this;
		}

		public Builder authorizationPolicy(ToolAuthorizationPolicy policy) {
			this.authorizationPolicy = Objects.requireNonNull(policy, "policy must not be null");
			return this;
		}

		public Builder validateToolArguments(boolean validate) {
			this.validateToolArguments = validate;
			return this;
		}

		public Builder patchPolicy(ToolContextPatchPolicy patchPolicy) {
			this.patchPolicy = patchPolicy;
			return this;
		}

		/**
		 * Opt-in strict tool-state mode. Tools then receive a read-only view of the
		 * invocation state instead of the live map (writes escape only through the
		 * {@link ToolContextPatchProvider} patch channel), and a failed call's patch is
		 * always discarded regardless of {@code discardPatchOnFailedResult}. Merging
		 * stays sequential last-write-wins, deliberately diverging from the graph path's
		 * KeyStrategy-honoring strict merges: the ReAct state is an invocation-local
		 * output map rather than the shared graph blackboard, and tool execution is
		 * sequential, so the result is already deterministic w.r.t. completion order.
		 * Default {@code false}: legacy behavior.
		 * @param strict true to enable strict tool-state semantics
		 * @return this builder
		 */
		public Builder strictToolState(boolean strict) {
			this.strictToolState = strict;
			return this;
		}

		public Builder outcomeMappers(BusinessOutcomeMapperRegistry outcomeMappers) {
			this.outcomeMappers = Objects.requireNonNull(outcomeMappers, "outcomeMappers must not be null");
			return this;
		}

		public Builder humanInTheLoop(HumanInTheLoop humanInTheLoop) {
			this.humanInTheLoop = humanInTheLoop;
			return this;
		}

		public Builder outputSchema(String outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		public Builder outputFinalizer(StructuredOutputFinalizer outputFinalizer) {
			this.outputFinalizer = outputFinalizer;
			return this;
		}

		public Builder maxTurns(int maxTurns) {
			if (maxTurns < 1) {
				throw new IllegalArgumentException("maxTurns must be positive");
			}
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder streamModel(boolean streamModel) {
			this.streamModel = streamModel;
			return this;
		}

		/** Resolves invocation-local tools after each completed tool turn. */
		public Builder dynamicTools(Function<Map<String, Object>, ToolRegistry> provider) {
			this.dynamicToolProvider = Objects.requireNonNull(provider, "provider must not be null");
			return this;
		}

		public ReActAgent build() {
			if (outputSchema != null && outputFinalizer == null) {
				outputFinalizer = new StructuredOutputFinalizer();
			}
			return new ReActAgent(this);
		}

	}

	private final class RunSubscription implements Flow.Subscription {

		private final AgentRequest request;

		private final AgentExecutionContext context;

		private final Flow.Subscriber<? super AgentEvent> subscriber;

		private final AtomicBoolean started = new AtomicBoolean();

		private final AtomicBoolean cancelled = new AtomicBoolean();

		private final AtomicReference<Flow.Subscription> modelSubscription = new AtomicReference<>();

		private final AtomicReference<CompletableFuture<?>> toolOperation = new AtomicReference<>();

		private RunSubscription(AgentRequest request, AgentExecutionContext context,
				Flow.Subscriber<? super AgentEvent> subscriber) {
			this.request = request;
			this.context = context;
			this.subscriber = Objects.requireNonNull(subscriber, "subscriber must not be null");
		}

		private void subscribe() {
			try {
				subscriber.onSubscribe(this);
			}
			catch (RuntimeException error) {
				cancel();
			}
		}

		@Override
		public void request(long demand) {
			if (demand <= 0) {
				if (started.compareAndSet(false, true)) {
					subscriber.onError(new IllegalArgumentException("demand must be positive"));
				}
				return;
			}
			if (!started.compareAndSet(false, true) || cancelled.get()) {
				return;
			}
			Thread worker = new Thread(this::runSafely, "argi-react-agent");
			worker.setDaemon(true);
			worker.start();
		}

		private void runSafely() {
			try {
				run();
				if (!cancelled.get()) {
					subscriber.onComplete();
				}
			}
			catch (Throwable error) {
				if (!cancelled.get()) {
					subscriber.onError(error);
				}
			}
		}

		@Override
		public void cancel() {
			if (cancelled.compareAndSet(false, true)) {
				context.control().cancel();
				Flow.Subscription subscription = modelSubscription.get();
				if (subscription != null) {
					subscription.cancel();
				}
				CompletableFuture<?> operation = toolOperation.get();
				if (operation != null) {
					operation.cancel(true);
				}
			}
		}

		private void run() {
			context.control().assertActive();
			if (request.resumeToken() != null) {
				if (humanInTheLoop == null) {
					throw new IllegalArgumentException("This ReAct agent does not support resume");
				}
				humanInTheLoop.validateResume(request, context);
			}
			List<ChatMessage> messages = new ArrayList<>(request.messages());
			Map<String, Object> state = new LinkedHashMap<>(request.inputs());
			UsageAccumulator usage = new UsageAccumulator();
			for (int turn = 0; turn < maxTurns; turn++) {
				context.control().assertActive();
				ToolRegistry availableTools = resolveTools(state);
				ChatResponse response = modelTurn(messages, state, usage, availableTools);
				if (response.messages().isEmpty()) {
					throw new IllegalStateException("Model response did not contain an assistant message");
				}
				for (ChatMessage message : response.messages()) {
					messages.add(message);
					emit(new AgentEvent.Message("model-" + turn, message));
				}
				if (response.toolCalls().isEmpty()) {
					finish(messages, state, usage.value(), response.messages().get(response.messages().size() - 1));
					return;
				}
				for (ToolCall call : response.toolCalls()) {
					ToolStep step = executeTool(call, messages, state, availableTools);
					if (step.suspension() != null) {
						AgentOutcome outcome = new AgentOutcome(AgentOutcome.Status.SUSPENDED, messages, state,
								usage.value(), step.suspension());
						emit(new AgentEvent.Finished(outcome));
						return;
					}
					if (step.returnDirect()) {
						finish(messages, state, usage.value(), messages.get(messages.size() - 1));
						return;
					}
				}
			}
			throw new IllegalStateException("ReAct agent exceeded the configured turn limit");
		}

		private ToolRegistry resolveTools(Map<String, Object> state) {
			if (dynamicToolProvider == null) {
				return tools;
			}
			ToolRegistry dynamic = Objects.requireNonNull(dynamicToolProvider.apply(Map.copyOf(state)),
					"dynamic tool provider returned no registry");
			List<Tool> combined = new ArrayList<>(tools.tools());
			combined.addAll(dynamic.tools());
			return new ToolRegistry(combined);
		}

		private ChatResponse modelTurn(List<ChatMessage> messages, Map<String, Object> state,
				UsageAccumulator usage, ToolRegistry availableTools) {
			context.control().assertActive();
			ModelRequest request = ModelRequest.builder().messages(messages).toolRegistry(availableTools)
				.trustedExecutionContext(context.trusted()).context(state).attributes(state).build();
			ModelCallHandler handler = modelRequest -> invokeModel(modelRequest, usage);
			for (int index = interceptors.size() - 1; index >= 0; index--) {
				ModelInterceptor interceptor = interceptors.get(index);
				ModelCallHandler next = handler;
				handler = current -> interceptor.interceptModel(current, next);
			}
			ModelResponse response = Objects.requireNonNull(handler.call(request), "model interceptor returned no response");
			if (response.isStreaming()) {
				return collectModelStream(response.getStream(), usage);
			}
			return response.getChatResponse();
		}

		private ModelResponse invokeModel(ModelRequest request, UsageAccumulator usage) {
			ChatRequest chatRequest = new ChatRequest(request.getMessages(), request.getToolRegistry().definitions(),
					request.getOptions(), request.getAttributes());
			if (streamModel) {
				return ModelResponse.of(collectModelStream(chatRequest, usage));
			}
			CompletableFuture<ChatResponse> future = chatClient.call(chatRequest).toCompletableFuture();
			toolOperation.set(future);
			try (AutoCloseable ignored = context.control().onLoss(() -> future.cancel(true))) {
				ChatResponse response = await(future);
				usage.add(response.usage());
				emit(new AgentEvent.UsageUpdate(response.usage()));
				return ModelResponse.of(response);
			}
			catch (Exception error) {
				throw rethrow(error);
			}
			finally {
				toolOperation.compareAndSet(future, null);
			}
		}

		private ChatResponse collectModelStream(ChatRequest request, UsageAccumulator usage) {
			return collectModelStream(Objects.requireNonNull(chatClient.stream(request), "chat client returned no stream"),
					usage);
		}

		private ChatResponse collectModelStream(Flow.Publisher<ChatEvent> publisher, UsageAccumulator usage) {
			CompletableFuture<ChatResponse> future = new CompletableFuture<>();
			try (AutoCloseable ignored = context.control().onLoss(() -> {
				Flow.Subscription subscription = modelSubscription.get();
				if (subscription != null) {
					subscription.cancel();
				}
				future.cancel(true);
			})) {
				publisher.subscribe(new Flow.Subscriber<>() {
					private ChatResponse response;

					@Override
					public void onSubscribe(Flow.Subscription subscription) {
						if (!modelSubscription.compareAndSet(null, subscription)) {
							subscription.cancel();
							return;
						}
						subscription.request(Long.MAX_VALUE);
					}

					@Override
					public void onNext(ChatEvent event) {
						context.control().assertActive();
						switch (event.type()) {
							case TEXT_DELTA -> emit(new AgentEvent.MessageDelta("model-stream", event.text()));
							case USAGE -> emit(new AgentEvent.UsageUpdate(event.usage()));
							case RESPONSE -> response = event.response();
							case TOOL_CALL -> emit(new AgentEvent.Progress("model.tool-call", Map.of("name",
									event.toolCall().name())));
						}
					}

					@Override
					public void onError(Throwable error) {
						future.completeExceptionally(error);
					}

					@Override
					public void onComplete() {
						if (response == null) {
							future.completeExceptionally(new IllegalStateException(
									"Model stream completed without a response"));
						}
						else {
							future.complete(response);
						}
					}

				});
				ChatResponse response = await(future);
				usage.add(response.usage());
				return response;
			}
			catch (Exception error) {
				throw rethrow(error);
			}
			finally {
				modelSubscription.set(null);
			}
		}

		private ToolStep executeTool(ToolCall call, List<ChatMessage> messages, Map<String, Object> state,
				ToolRegistry availableTools) {
			context.control().assertActive();
			AuthorizationDecision decision = authorizationPolicy.authorize(call.name(), context.trusted());
			if (decision.isDenied()) {
				if (humanInTheLoop == null) {
					ToolResult denied = ToolResult.error(decision.reason());
					appendToolResult(call, denied, messages, state, null);
					return new ToolStep(false, null);
				}
				return new ToolStep(false, humanInTheLoop.suspend(new HumanInTheLoop.HumanRequest(
						decision.reason(), call.name(), call.id(), Map.of()), context));
			}
			Tool tool = availableTools.find(call.name()).orElse(null);
			if (tool == null) {
				appendToolResult(call, ToolResult.error("Unknown tool: " + call.name()), messages, state, null);
				return new ToolStep(false, null);
			}
			if (validateToolArguments) {
				var failure = ToolArgumentValidator.validate(tool.definition().inputSchema(), call.arguments());
				if (failure.isPresent()) {
					ToolResult invalid = new ToolResult(List.of(io.github.agentic.ai.api.chat.ContentPart.text(
							"Tool arguments were rejected")), true,
							Map.of("errorCode", failure.get().errorCode(), "fieldPath", failure.get().fieldPath()));
					appendToolResult(call, invalid, messages, state, tool);
					return new ToolStep(false, null);
				}
			}
			Map<String, Object> toolVisibleState = strictToolState ? StateIsolation.readOnlyView(state) : state;
			CompletableFuture<ToolResult> future = availableTools.execute(call, new ToolContext(toolVisibleState))
				.toCompletableFuture();
			toolOperation.set(future);
			try (AutoCloseable ignored = context.control().onLoss(() -> future.cancel(true))) {
				ToolResult result;
				try {
					result = outcomeMappers.mapOutcome(call, await(future));
				}
				catch (Exception error) {
					context.control().assertActive();
					result = ToolResult.error("Tool execution failed");
				}
				appendToolResult(call, result, messages, state, tool);
				return new ToolStep(tool.returnDirect(), null);
			}
			catch (Exception error) {
				throw rethrow(error);
			}
			finally {
				toolOperation.compareAndSet(future, null);
			}
		}

		private void appendToolResult(ToolCall call, ToolResult result, List<ChatMessage> messages,
				Map<String, Object> state, Tool tool) {
				if (tool instanceof ToolContextPatchProvider provider) {
					Map<String, Object> patch = provider.contextPatch(call, result);
					admitPatch(call.name(), result, patch, state);
				}
			ChatMessage message = result.toMessage(call);
			messages.add(message);
			emit(new AgentEvent.Message("tool-" + call.id(), message));
		}

		private void admitPatch(String toolName, ToolResult result, Map<String, Object> patch,
				Map<String, Object> state) {
			if (patch == null || patch.isEmpty()) {
				return;
			}
			if (strictToolState) {
				// Hard rule: a failed call's patch is always discarded, independent of
				// discardPatchOnFailedResult; the shared admission also applies the policy
				// validation, so both paths enforce identical rules.
				if (StrictPatchAdmission.admit(patchPolicy,
						new ToolUpdateUnit(toolName, !result.error(), patch))) {
					state.putAll(patch);
				}
				return;
			}
			if (patchPolicy != null && result.error() && patchPolicy.discardPatchOnFailedResult()) {
				return;
			}
			if (patchPolicy != null) {
				var failure = ToolContextPatchValidator.validate(patchPolicy.specFor(toolName), toolName, patch);
				if (failure.isPresent()) {
					if (patchPolicy.violationPolicy() == ToolContextPatchPolicy.ViolationPolicy.ABORT_RUN) {
						throw new ToolContextPatchViolationException(failure.get());
					}
					return;
				}
			}
			state.putAll(patch);
		}

		private void finish(List<ChatMessage> messages, Map<String, Object> state, Usage usage,
				ChatMessage terminal) {
			Map<String, Object> outputs = new LinkedHashMap<>(state);
			if (outputSchema != null && outputFinalizer != null) {
				AgentResult result = outputFinalizer.finalizeOutput(terminal, outputSchema, usage);
				if (result.status() != AgentResult.Status.COMPLETED) {
					throw new AgentExecutionException(result);
				}
				if (result.data() != null) {
					outputs.put("structuredOutput", result.data());
				}
			}
			emit(new AgentEvent.Finished(AgentOutcome.completed(messages, outputs, usage)));
		}

		private void emit(AgentEvent event) {
			if (!cancelled.get()) {
				subscriber.onNext(event);
			}
		}

	}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get();
		}
		catch (InterruptedException interrupted) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new CancellationException("Agent execution interrupted");
		}
		catch (ExecutionException error) {
			throw rethrow(error.getCause());
		}
	}

	private static RuntimeException rethrow(Throwable error) {
		if (error instanceof RuntimeException runtime) {
			return runtime;
		}
		if (error instanceof Error fatal) {
			throw fatal;
		}
		return new CompletionException(error);
	}

	private record ToolStep(boolean returnDirect, AgentOutcome.Suspension suspension) {
	}

	private static final class UsageAccumulator {

		private Long input = 0L;

		private Long output = 0L;

		private Long total = 0L;

		private void add(Usage usage) {
			input = add(input, usage.inputTokens());
			output = add(output, usage.outputTokens());
			total = add(total, usage.totalTokens());
		}

		private Usage value() {
			return new Usage(input, output, total);
		}

		private static Long add(Long left, Long right) {
			return left == null || right == null ? null : left + right;
		}

	}

}
