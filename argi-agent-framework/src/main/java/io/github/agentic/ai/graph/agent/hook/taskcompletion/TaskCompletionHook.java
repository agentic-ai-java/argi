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

package io.github.agentic.ai.graph.agent.hook.taskcompletion;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.action.ActionDecision;
import io.github.agentic.ai.graph.agent.action.ActionReasonCodes;
import io.github.agentic.ai.graph.agent.action.ActionType;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.HookPositions;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.ModelHook;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Opt-in hook that refuses to let a run with unfinished required tasks end as a plain
 * success just because the model produced no tool call (T36).
 *
 * <p>Register the hook through the existing builder, e.g.
 * {@code ReactAgent.builder().hooks(TaskCompletionHook.builder().build())}. When the
 * completion predicate reports unfinished work and the model answered without tool
 * calls, the hook appends one nudge user message and jumps back to the model, at most
 * {@link Builder#maxAttempts(int)} times and never beyond {@link Builder#deadline(Duration)}.
 * Once that budget is exhausted the run terminates with an explicit terminal decision
 * under {@link #TERMINAL_DECISION_STATE_KEY} (default {@code NEED_INPUT}) instead of an
 * implicit success. Identical consecutive decisions and unresolvable tool calls stop the
 * run with {@code STOP} reason codes. All counters are run-scoped and never enter the
 * checkpointed state.</p>
 */
@HookPositions({ HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL })
public class TaskCompletionHook extends ModelHook {

	/**
	 * State key holding the terminal {@link ActionDecision} of the most recent run. The
	 * key only exists when this hook is registered and terminates a run.
	 */
	public static final String TERMINAL_DECISION_STATE_KEY = "argi.actionDecision.final";

	private static final String MESSAGES_STATE_KEY = "messages";

	private static final String JUMP_TO_STATE_KEY = "jump_to";

	private static final String NUDGE_COUNT_CONTEXT_KEY = "__task_completion_nudge_count__";

	private static final String LAST_FINGERPRINT_CONTEXT_KEY = "__task_completion_last_fingerprint__";

	private static final String REPEAT_COUNT_CONTEXT_KEY = "__task_completion_repeat_count__";

	private static final String DEADLINE_START_CONTEXT_KEY = "__task_completion_deadline_start_nanos__";

	private static final String MESSAGE_BASELINE_CONTEXT_KEY = "__task_completion_message_baseline__";

	private static final String PENDING_ERROR_CONTEXT_KEY = "__task_completion_pending_error__";

	private static final String TERMINAL_WRITTEN_CONTEXT_KEY = "__task_completion_terminal_written__";

	private final Predicate<OverAllState> completionPredicate;

	private final int maxAttempts;

	private final Duration deadline;

	private final int maxConsecutiveRepeats;

	private final Integer maxUnknownToolCalls;

	private final TerminalAction onUnfinishedExhaustion;

	private final ExitBehavior exitBehavior;

	private final Function<List<String>, String> nudgeText;

	private final LongSupplier nanoTime;

	TaskCompletionHook(Builder builder) {
		this.completionPredicate = builder.completionPredicate;
		this.maxAttempts = builder.maxAttempts;
		this.deadline = builder.deadline;
		this.maxConsecutiveRepeats = builder.maxConsecutiveRepeats;
		this.maxUnknownToolCalls = builder.maxUnknownToolCalls;
		this.onUnfinishedExhaustion = builder.onUnfinishedExhaustion;
		this.exitBehavior = builder.exitBehavior;
		this.nudgeText = builder.nudgeText;
		this.nanoTime = builder.nanoTime;
	}

	/**
	 * Creates a new builder with defaults: todos-based completion predicate, two nudge
	 * attempts, no deadline, repeat bound three, unknown-tool bound disabled, terminal
	 * action {@link TerminalAction#NEED_INPUT} and {@link ExitBehavior#END}.
	 * @return the builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String getName() {
		return "TaskCompletion";
	}

	@Override
	public List<JumpTo> canJumpTo() {
		return List.of(JumpTo.model, JumpTo.end);
	}

	@Override
	public Map<String, KeyStrategy> getKeyStrategys() {
		return Map.of(TERMINAL_DECISION_STATE_KEY, KeyStrategy.REPLACE);
	}

	/**
	 * Cheap pre-call guards: surfaces a pending terminal error in
	 * {@link ExitBehavior#ERROR} mode and enforces the optional unknown-tool bound.
	 */
	@Override
	public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
		Object pending = config.context().remove(PENDING_ERROR_CONTEXT_KEY);
		if (pending instanceof TaskCompletionExceededException error) {
			throw error;
		}
		if (isTerminalWritten(config)) {
			return CompletableFuture.completedFuture(Map.of());
		}
		List<ChatMessage> messages = messages(state);
		Object baselineValue = config.context().get(MESSAGE_BASELINE_CONTEXT_KEY);
		if (!(baselineValue instanceof Integer baseline)) {
			config.context().put(MESSAGE_BASELINE_CONTEXT_KEY, messages.size());
			// A fresh run clears any terminal decision left over from the previous run on
			// the same thread, so the key always reflects the latest run.
			return CompletableFuture
				.completedFuture(Map.of(TERMINAL_DECISION_STATE_KEY, OverAllState.MARK_FOR_REMOVAL));
		}
		if (maxUnknownToolCalls == null) {
			return CompletableFuture.completedFuture(Map.of());
		}
		int unresolved = countUnresolvedToolMessages(messages, baseline);
		if (unresolved > maxUnknownToolCalls) {
			ActionDecision decision = ActionDecision.of(ActionType.STOP, ActionReasonCodes.UNKNOWN_TOOL,
					unresolved + " tool calls to unknown tools exceeded the bound of " + maxUnknownToolCalls);
			return CompletableFuture
				.completedFuture(terminalExit(config, decision, TaskCompletionPredicates.unfinishedTodoLines(state)));
		}
		return CompletableFuture.completedFuture(Map.of());
	}

	/**
	 * Routing-time guard: refuses a plain finish while required tasks are unfinished,
	 * nudges the model back (bounded), and stops repeated or unproductive loops.
	 */
	@Override
	public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
		if (isTerminalWritten(config)) {
			return CompletableFuture.completedFuture(Map.of());
		}
		List<ChatMessage> messages = messages(state);
		if (messages.isEmpty()) {
			return CompletableFuture.completedFuture(Map.of());
		}
		ChatMessage last = messages.get(messages.size() - 1);
		if (last.role() != ChatRole.ASSISTANT) {
			return CompletableFuture.completedFuture(Map.of());
		}

		int repeatCount = trackDecisionFingerprint(last, config);
		List<String> unfinishedLines = TaskCompletionPredicates.unfinishedTodoLines(state);
		boolean workUnfinished = !completionPredicate.test(state);
		int nudges = intFrom(config.context().get(NUDGE_COUNT_CONTEXT_KEY));
		boolean budgetExhausted = workUnfinished && !last.hasToolCalls()
				&& (nudges >= maxAttempts || isDeadlineExceeded(config));

		if (budgetExhausted) {
			// Classified first so an exhausted nudge loop with identical answers reports
			// NO_PROGRESS rather than the generic repeat guard.
			ActionType action = onUnfinishedExhaustion == TerminalAction.NEED_INPUT ? ActionType.NEED_INPUT
					: ActionType.STOP;
			String reasonCode = repeatCount >= maxAttempts + 1 ? ActionReasonCodes.NO_PROGRESS
					: ActionReasonCodes.UNFINISHED_REQUIRED_TASKS;
			ActionDecision decision = ActionDecision.of(action, reasonCode,
					"Unfinished work remains after " + nudges + " nudges"
							+ (deadline != null ? " or its deadline" : ""));
			return CompletableFuture.completedFuture(terminalExit(config, decision, unfinishedLines));
		}

		if (repeatCount >= maxConsecutiveRepeats) {
			ActionDecision decision = ActionDecision.of(ActionType.STOP, ActionReasonCodes.REPEATED_DECISION,
					"The same decision repeated " + repeatCount + " consecutive times");
			return CompletableFuture.completedFuture(terminalExit(config, decision, unfinishedLines));
		}

		if (last.hasToolCalls()) {
			// A productive turn resets the nudge budget.
			config.context().put(NUDGE_COUNT_CONTEXT_KEY, 0);
			return CompletableFuture.completedFuture(Map.of());
		}

		if (!workUnfinished) {
			return CompletableFuture.completedFuture(Map.of());
		}

		if (nudges < maxAttempts && !isDeadlineExceeded(config)) {
			if (deadline != null) {
				config.context().putIfAbsent(DEADLINE_START_CONTEXT_KEY, nanoTime.getAsLong());
			}
			config.context().put(NUDGE_COUNT_CONTEXT_KEY, nudges + 1);
			Map<String, Object> updates = new HashMap<>();
			updates.put(MESSAGES_STATE_KEY, List.of(ChatMessage.user(nudgeText.apply(unfinishedLines))));
			updates.put(JUMP_TO_STATE_KEY, JumpTo.model);
			return CompletableFuture.completedFuture(updates);
		}

		// Unreachable in practice: an exhausted budget is handled before the nudge branch.
		return CompletableFuture.completedFuture(Map.of());
	}

	private Map<String, Object> terminalExit(RunnableConfig config, ActionDecision decision,
			List<String> unfinishedLines) {
		config.context().put(TERMINAL_WRITTEN_CONTEXT_KEY, Boolean.TRUE);
		Map<String, Object> updates = new HashMap<>();
		updates.put(MESSAGES_STATE_KEY, List.of(ChatMessage.assistant(terminalMessage(decision, unfinishedLines))));
		updates.put(TERMINAL_DECISION_STATE_KEY, decision);
		if (exitBehavior == ExitBehavior.ERROR) {
			// Route toward the next beforeModel so the terminal state is checkpointed
			// before the error surfaces there.
			config.context().put(PENDING_ERROR_CONTEXT_KEY, new TaskCompletionExceededException(decision));
			updates.put(JUMP_TO_STATE_KEY, JumpTo.model);
		}
		else {
			updates.put(JUMP_TO_STATE_KEY, JumpTo.end);
		}
		return updates;
	}

	private String terminalMessage(ActionDecision decision, List<String> unfinishedLines) {
		StringBuilder text = new StringBuilder("Run terminated with ").append(decision.type())
			.append(" (")
			.append(decision.reasonCode())
			.append(").");
		if (!unfinishedLines.isEmpty()) {
			text.append(" Unfinished tasks:");
			for (String line : unfinishedLines) {
				text.append("\n- ").append(line);
			}
		}
		return text.toString();
	}

	private int trackDecisionFingerprint(ChatMessage message, RunnableConfig config) {
		String fingerprint = decisionFingerprint(message);
		boolean repeated = fingerprint.equals(config.context().get(LAST_FINGERPRINT_CONTEXT_KEY));
		int repeatCount = repeated ? intFrom(config.context().get(REPEAT_COUNT_CONTEXT_KEY)) + 1 : 1;
		config.context().put(LAST_FINGERPRINT_CONTEXT_KEY, fingerprint);
		config.context().put(REPEAT_COUNT_CONTEXT_KEY, repeatCount);
		return repeatCount;
	}

	private String decisionFingerprint(ChatMessage message) {
		if (!message.hasToolCalls()) {
			return "final:" + message.text();
		}
		return "tools:" + message.toolCalls()
			.stream()
			.map(call -> call.name() + ":" + call.arguments())
			.sorted()
			.collect(Collectors.joining(","));
	}

	private boolean isDeadlineExceeded(RunnableConfig config) {
		if (deadline == null) {
			return false;
		}
		if (!(config.context().get(DEADLINE_START_CONTEXT_KEY) instanceof Long start)) {
			return false;
		}
		return nanoTime.getAsLong() - start > deadline.toNanos();
	}

	private boolean isTerminalWritten(RunnableConfig config) {
		return Boolean.TRUE.equals(config.context().get(TERMINAL_WRITTEN_CONTEXT_KEY));
	}

	private int countUnresolvedToolMessages(List<ChatMessage> messages, int baseline) {
		int unresolved = 0;
		for (int i = baseline; i < messages.size(); i++) {
			ChatMessage message = messages.get(i);
			if (message.role() == ChatRole.TOOL && message.metadata() != null
					&& ToolCallResponse.FAILURE_KIND_UNRESOLVED
						.equals(message.metadata().get(ToolCallResponse.FAILURE_KIND_METADATA_KEY))) {
				unresolved++;
			}
		}
		return unresolved;
	}

	@SuppressWarnings("unchecked")
	private static List<ChatMessage> messages(OverAllState state) {
		return (List<ChatMessage>) state.value(MESSAGES_STATE_KEY).orElse(List.of());
	}

	private static int intFrom(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	/**
	 * How a bounded exit terminates the run.
	 */
	public enum ExitBehavior {

		/** Append a terminal assistant message, record the terminal decision, jump to end. */
		END,

		/**
		 * Record the terminal state first, then throw
		 * {@link TaskCompletionExceededException} before the next model call so the
		 * terminal state is checkpointed before the error surfaces.
		 */
		ERROR

	}

	/**
	 * Terminal action recorded when the unfinished-task re-ask budget is exhausted.
	 */
	public enum TerminalAction {

		/** The run cannot proceed without additional external input. */
		NEED_INPUT,

		/** Stop the run without widening the execution scope. */
		STOP

	}

	/**
	 * Builder for {@link TaskCompletionHook}.
	 */
	public static class Builder {

		private Predicate<OverAllState> completionPredicate = TaskCompletionPredicates.todosComplete();

		private int maxAttempts = 2;

		private Duration deadline;

		private int maxConsecutiveRepeats = 3;

		private Integer maxUnknownToolCalls;

		private TerminalAction onUnfinishedExhaustion = TerminalAction.NEED_INPUT;

		private ExitBehavior exitBehavior = ExitBehavior.END;

		private Function<List<String>, String> nudgeText = TaskCompletionHook::defaultNudgeText;

		private LongSupplier nanoTime = System::nanoTime;

		/**
		 * Sets the predicate deciding whether the run's required work is complete.
		 * @param predicate returns {@code true} when the work is complete
		 * @return this builder
		 */
		public Builder completionPredicate(Predicate<OverAllState> predicate) {
			this.completionPredicate = Objects.requireNonNull(predicate, "completionPredicate must not be null");
			return this;
		}

		/**
		 * Sets how many nudge re-asks are allowed before the run terminates with an
		 * explicit terminal decision.
		 * @param maxAttempts the maximum number of nudges, at least 1
		 * @return this builder
		 */
		public Builder maxAttempts(int maxAttempts) {
			if (maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be at least 1");
			}
			this.maxAttempts = maxAttempts;
			return this;
		}

		/**
		 * Sets an optional wall-clock budget for the whole nudge loop, measured from the
		 * first nudge. No deadline is applied by default.
		 * @param deadline a positive duration, or {@code null} to disable
		 * @return this builder
		 */
		public Builder deadline(Duration deadline) {
			if (deadline != null && (deadline.isZero() || deadline.isNegative())) {
				throw new IllegalArgumentException("deadline must be positive when set");
			}
			this.deadline = deadline;
			return this;
		}

		/**
		 * Sets how many consecutive identical decisions (identical tool calls or an
		 * identical final answer) are tolerated before the run stops.
		 * @param maxConsecutiveRepeats the tolerated repeat count, at least 2
		 * @return this builder
		 */
		public Builder maxConsecutiveRepeats(int maxConsecutiveRepeats) {
			if (maxConsecutiveRepeats < 2) {
				throw new IllegalArgumentException("maxConsecutiveRepeats must be at least 2");
			}
			this.maxConsecutiveRepeats = maxConsecutiveRepeats;
			return this;
		}

		/**
		 * Sets an optional bound on tool calls to unresolvable tool names in this run.
		 * Disabled by default.
		 * @param maxUnknownToolCalls the tolerated unresolved count, at least 1, or
		 * {@code null} to disable
		 * @return this builder
		 */
		public Builder maxUnknownToolCalls(Integer maxUnknownToolCalls) {
			if (maxUnknownToolCalls != null && maxUnknownToolCalls < 1) {
				throw new IllegalArgumentException("maxUnknownToolCalls must be at least 1 when set");
			}
			this.maxUnknownToolCalls = maxUnknownToolCalls;
			return this;
		}

		/**
		 * Sets the terminal action recorded when the unfinished-task budget is exhausted.
		 * @param action the terminal action
		 * @return this builder
		 */
		public Builder onUnfinishedExhaustion(TerminalAction action) {
			this.onUnfinishedExhaustion = Objects.requireNonNull(action, "action must not be null");
			return this;
		}

		/**
		 * Sets how a bounded exit terminates the run.
		 * @param exitBehavior the exit behavior
		 * @return this builder
		 */
		public Builder exitBehavior(ExitBehavior exitBehavior) {
			this.exitBehavior = Objects.requireNonNull(exitBehavior, "exitBehavior must not be null");
			return this;
		}

		/**
		 * Sets the renderer for the nudge user message. It receives one operational line
		 * per unfinished todo (empty for custom predicates without todos).
		 * @param nudgeText renders the nudge message text
		 * @return this builder
		 */
		public Builder nudgeText(Function<List<String>, String> nudgeText) {
			this.nudgeText = Objects.requireNonNull(nudgeText, "nudgeText must not be null");
			return this;
		}

		Builder nanoTime(LongSupplier nanoTime) {
			this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
			return this;
		}

		/**
		 * Builds the hook.
		 * @return the hook
		 */
		public TaskCompletionHook build() {
			return new TaskCompletionHook(this);
		}

	}

	static String defaultNudgeText(List<String> unfinishedLines) {
		StringBuilder text = new StringBuilder("Required tasks are not complete.");
		if (!unfinishedLines.isEmpty()) {
			text.append(" Unfinished:");
			for (String line : unfinishedLines) {
				text.append("\n- ").append(line);
			}
		}
		text.append(
				"\nContinue with the available tools, mark finished tasks completed, or state exactly what input you need.");
		return text.toString();
	}

}
