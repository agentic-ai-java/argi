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
package io.github.agentic.ai.agent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Engine-neutral invocation boundary for local, workflow and remote agents. No
 * implementation may silently route this contract through Graph.
 */
@FunctionalInterface
public interface Agent {

	/**
	 * Returns a cold, single-subscriber execution. Work starts on subscription and
	 * respects Flow demand and cancellation. Emit exactly one Finished event followed by
	 * onComplete, or terminate with onError. No events may follow Finished. Errors,
	 * including ExecutionStoppedException, are exceptional termination, not successful
	 * outcomes. Implementations must reject unsupported resume requests. They must check
	 * the control before side effects and propagate cancellation to in-flight model, tool
	 * or transport operations; subscriber cancellation alone does not necessarily cancel
	 * the caller-owned context.
	 * @param request business input, never an authentication source
	 * @param context authenticated constraints and cooperative execution control
	 * @return the execution event stream
	 */
	Flow.Publisher<AgentEvent> stream(AgentRequest request, AgentExecutionContext context);

	/**
	 * Consumes one execution, discarding intermediate events without buffering them. The
	 * future succeeds only after Finished followed by onComplete. Cancelling the future
	 * cancels this context and its subscription. The caller closes the context; use a
	 * separate context for each independently cancellable invocation. Deadline checks are
	 * cooperative; this helper creates no timer or executor.
	 * @param request business input
	 * @param context caller-owned execution context
	 * @return the terminal outcome, or an exceptional/cancelled future
	 */
	default CompletableFuture<AgentOutcome> invoke(AgentRequest request, AgentExecutionContext context) {
		return AgentInvocation.invoke(this, request, context);
	}

}
