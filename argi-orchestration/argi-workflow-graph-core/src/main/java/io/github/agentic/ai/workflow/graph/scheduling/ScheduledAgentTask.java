/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.workflow.graph.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.agentic.ai.workflow.graph.CompiledGraph;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.OverAllStateBuilder;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * ScheduledGraphExecution
 *
 * @author yaohui &#064;create 2025/8/20 15:04
 **/
public class ScheduledAgentTask {

	private static final Logger log = LoggerFactory.getLogger(CompiledGraph.class);

	private final ScheduledAgentManager registrationManager;

	private final TaskScheduler taskScheduler;

	private final CompiledGraph graph;

	private final ScheduleConfig config;

	private volatile ScheduledFuture<?> scheduledFuture;

	/**
	 * Atomic lifecycle state (issue #215): NEW → STARTING → RUNNING → STOPPING →
	 * STOPPED, with FAILED as the start-failure terminal. A single CAS-able state
	 * replaces the previous independent started/stopped flags, so concurrent start
	 * and stop transitions cannot interleave.
	 */
	enum ScheduleState {

		/** Created and registered, not yet scheduled. */
		NEW,

		/** A start is publishing the schedule; stop may still win the race. */
		STARTING,

		/** The schedule is live. */
		RUNNING,

		/** stop() is cancelling and unregistering. */
		STOPPING,

		/** Terminal after a successful stop; start is rejected. */
		STOPPED,

		/** Terminal after a failed start; registration has been released. */
		FAILED

	}

	private final AtomicReference<ScheduleState> state = new AtomicReference<>(ScheduleState.NEW);

	/**
	 * Monotonic observation projection, not a lifecycle transition: set once when a
	 * start reaches RUNNING and never reset, so {@link #isStarted()} reports whether a
	 * start actually succeeded rather than which non-terminal state the task is in.
	 * The state machine above stays the single source of transitions (#370 review).
	 */
	private volatile boolean startSucceeded;

	private final AtomicBoolean unregistered = new AtomicBoolean(false);

	private final String taskId;

	public ScheduledAgentTask(CompiledGraph graph, ScheduleConfig config) {
		this.graph = graph;
		this.config = config;
		this.registrationManager = ScheduledAgentManagerFactory.getInstance().getManager();
		this.taskScheduler = registrationManager.getTaskScheduler();
		// Retain the registration owner even if the factory's active manager changes.
		this.taskId = registrationManager.registerTask(this);
		log.debug("Created ScheduledAgentTask with ID: {}", taskId);
	}

	/**
	 * Start the scheduled execution
	 */
	public ScheduledAgentTask start() {
		if (!state.compareAndSet(ScheduleState.NEW, ScheduleState.STARTING)) {
			throw new IllegalStateException("Schedule already started or stopped: " + state.get());
		}

		try {
			switch (config.getMode()) {
				case CRON:
					scheduledFuture = taskScheduler.schedule(this::executeGraph,
							new CronTrigger(config.getCronExpression()));
					break;
				case FIXED_DELAY:
					scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::executeGraph,
							Instant.now().plusMillis(config.getInitialDelay()), Duration.ofMillis(config.getFixedDelay()));
					break;
				case FIXED_RATE:
					scheduledFuture = taskScheduler.scheduleAtFixedRate(this::executeGraph,
							Instant.now().plusMillis(config.getInitialDelay()), Duration.ofMillis(config.getFixedRate()));
					break;
				case ONE_TIME:
					scheduledFuture = taskScheduler.schedule(this::executeGraph,
							Instant.now().plusMillis(config.getInitialDelay()));
					break;
				case TRIGGER:
					scheduledFuture = taskScheduler.schedule(this::executeGraph, config.getTrigger());
					break;
				default:
					throw new IllegalStateException("Unexpected value: " + config.getMode());
			}
		}
		catch (RuntimeException e) {
			// A failed start must not leave the task registered or scheduled (#215).
			// CAS, not set: if stop() already won the STARTING window it owns the
			// terminal state, and FAILED must not clobber its STOPPED.
			state.compareAndSet(ScheduleState.STARTING, ScheduleState.FAILED);
			unregisterTask();
			throw e;
		}

		if (!state.compareAndSet(ScheduleState.STARTING, ScheduleState.RUNNING)) {
			// stop() won the race while the future was being published: cancel the late
			// publication so it cannot trigger new executions, and finish stopped.
			cancelPublishedFuture();
			throw new IllegalStateException("Schedule stopped while starting");
		}
		startSucceeded = true;

		notifyListeners(ScheduleLifecycleListener.ScheduleEvent.STARTED);
		return this;
	}

	/**
	 * Stop the scheduled execution
	 */
	public void stop() {
		// One winner per stop: concurrent and repeated stops return idempotently.
		if (!transitionToStopping()) {
			return;
		}
		cancelPublishedFuture();

		// Unregister from the manager that owns this task.
		unregisterTask();
		log.debug("Stopped and unregistered ScheduledAgentTask with ID: {}", taskId);

		notifyListeners(ScheduleLifecycleListener.ScheduleEvent.STOPPED);
		state.set(ScheduleState.STOPPED);
	}

	/**
	 * Moves a non-terminal state to STOPPING under one CAS winner, so a concurrent or
	 * repeated stop is a no-op.
	 * @return true if this caller won the transition and must perform the stop work
	 */
	private boolean transitionToStopping() {
		while (true) {
			ScheduleState current = state.get();
			if (current == ScheduleState.STOPPING || current == ScheduleState.STOPPED
					|| current == ScheduleState.FAILED) {
				return false;
			}
			if (state.compareAndSet(current, ScheduleState.STOPPING)) {
				return true;
			}
		}
	}

	/** Cancels the published future if present; safe against a late publication race. */
	private void cancelPublishedFuture() {
		ScheduledFuture<?> active = scheduledFuture;
		if (active != null && !active.isCancelled()) {
			active.cancel(false);
		}
	}

	/**
	 * Execute the graph
	 * @param runnableConfig
	 * @param inputs
	 */
	public void execute(RunnableConfig runnableConfig, Map<String, Object> inputs) {
		try {
			executeGraphAttempt(runnableConfig, inputs);
		}
		catch (Exception e) {
			// Public manual execution historically reports failures through listeners
			// without throwing to callers.
		}
	}

	/**
	 * Execute the graph with retry logic
	 */
	private void executeGraph() {
		try {
			int attempt = 0;
			while (attempt <= config.getMaxRetries()) {
				try {
					executeGraphAttempt(config.getRunnableConfig(), config.getInputs());
					return;
				}
				catch (Exception e) {
					log.warn("Graph execution failed (attempt {}): {}", attempt + 1, e.getMessage());
					if (attempt < config.getMaxRetries() && config.getRetryPredicate().apply(e)) {
						attempt++;
						try {
							Thread.sleep(config.getRetryDelay().toMillis());
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							break;
						}
					}
					else {
						break;
					}
				}
			}
		}
		finally {
			if (config.getMode() == ScheduleConfig.ScheduleMode.ONE_TIME) {
				unregisterTask();
			}
		}
	}

	private Optional<OverAllState> executeGraphAttempt(RunnableConfig runnableConfig, Map<String, Object> inputs) {
		try {
			notifyListeners(ScheduleLifecycleListener.ScheduleEvent.EXECUTION_STARTED);
			OverAllState initialState = createInitialState(inputs);
			if (runnableConfig == null) {
				String threadId = String.format("%s-%d", taskId, System.currentTimeMillis());
				runnableConfig = RunnableConfig.builder().threadId(threadId).build();
			}
			Optional<OverAllState> result = graph.invoke(initialState, runnableConfig);
			notifyListeners(ScheduleLifecycleListener.ScheduleEvent.EXECUTION_COMPLETED, result.orElse(null));
			return result;
		}
		catch (Exception e) {
			log.error("Graph execution failed", e);
			notifyListeners(ScheduleLifecycleListener.ScheduleEvent.EXECUTION_FAILED, e);
			throw e;
		}
	}

	private OverAllState createInitialState(Map<String, Object> inputs) {
		return OverAllStateBuilder.builder()
			.withKeyStrategies(graph.getKeyStrategyMap())
			.withData(inputs)
			.build();
	}

	private void notifyListeners(ScheduleLifecycleListener.ScheduleEvent event, Object data) {
		config.getListeners().forEach(listener -> {
			try {
				listener.onEvent(event, data);
			}
			catch (Exception e) {
				log.error("Error in schedule listener", e);
			}
		});
	}

	private void notifyListeners(ScheduleLifecycleListener.ScheduleEvent event) {
		notifyListeners(event, null);
	}

	private void unregisterTask() {
		if (unregistered.compareAndSet(false, true)) {
			registrationManager.unregisterTask(taskId);
		}
	}

	// Getters for monitoring
	public boolean isStarted() {
		return startSucceeded;
	}

	public boolean isStopped() {
		return state.get() == ScheduleState.STOPPING || state.get() == ScheduleState.STOPPED;
	}

	/** Test observation point for the lifecycle state machine. */
	ScheduleState state() {
		return state.get();
	}

	/**
	 * Get the unique task ID assigned by the registration manager
	 */
	public String getTaskId() {
		return taskId;
	}

	/**
	 * Get the name of the agent associated with this task
	 */
	public String getName() {
		return graph.stateGraph.getName();
	}

}
