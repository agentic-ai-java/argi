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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded-memory terminal collector shared by all implementations of Agent. */
final class AgentInvocation implements Flow.Subscriber<AgentEvent> {

	private final AgentExecutionContext context;

	private final CompletableFuture<AgentOutcome> result = new CompletableFuture<>();

	private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

	private AgentOutcome outcome;

	private AgentInvocation(AgentExecutionContext context) {
		this.context = Objects.requireNonNull(context, "context must not be null");
	}

	static CompletableFuture<AgentOutcome> invoke(Agent agent, AgentRequest request, AgentExecutionContext context) {
		Objects.requireNonNull(request, "request must not be null");
		AgentInvocation invocation = new AgentInvocation(context);
		AutoCloseable registration = context.control().onLoss(invocation::checkActive);
		invocation.result.whenComplete((value, failure) -> {
			try {
				registration.close();
			}
			catch (Exception exception) {
				if (failure != null && exception != failure) {
					failure.addSuppressed(exception);
				}
			}
			finally {
				if (invocation.result.isCancelled()) {
					context.control().cancel();
				}
				invocation.cancelSubscription();
			}
		});
		try {
			context.control().assertActive();
			Objects.requireNonNull(agent.stream(request, context), "agent stream must not be null")
				.subscribe(invocation);
		}
		catch (RuntimeException exception) {
			invocation.result.completeExceptionally(exception);
		}
		return invocation.result;
	}

	private void checkActive() {
		try {
			context.control().assertActive();
		}
		catch (RuntimeException exception) {
			result.completeExceptionally(exception);
		}
	}

	@Override
	public void onSubscribe(Flow.Subscription next) {
		Objects.requireNonNull(next, "subscription must not be null");
		if (!subscription.compareAndSet(null, next)) {
			next.cancel();
			result.completeExceptionally(new IllegalStateException("Agent subscribed more than once"));
			return;
		}
		checkActive();
		if (result.isDone()) {
			cancelSubscription();
		}
		else {
			try {
				next.request(Long.MAX_VALUE);
			}
			catch (RuntimeException exception) {
				result.completeExceptionally(exception);
			}
		}
	}

	@Override
	public synchronized void onNext(AgentEvent event) {
		if (result.isDone()) {
			return;
		}
		try {
			context.control().assertActive();
			Objects.requireNonNull(event, "event must not be null");
			if (subscription.get() == null || outcome != null) {
				throw new IllegalStateException("Agent event outside its execution sequence");
			}
			if (event instanceof AgentEvent.Finished finished) {
				outcome = finished.outcome();
			}
		}
		catch (RuntimeException exception) {
			result.completeExceptionally(exception);
		}
	}

	@Override
	public synchronized void onError(Throwable failure) {
		result.completeExceptionally(Objects.requireNonNull(failure, "failure must not be null"));
	}

	@Override
	public synchronized void onComplete() {
		if (result.isDone()) {
			return;
		}
		checkActive();
		if (outcome == null) {
			result.completeExceptionally(new IllegalStateException("Agent completed without an outcome"));
		}
		else {
			result.complete(outcome);
		}
	}

	private void cancelSubscription() {
		Flow.Subscription current = subscription.getAndSet(Cancelled.INSTANCE);
		if (current != null) {
			current.cancel();
		}
	}

	private enum Cancelled implements Flow.Subscription {

		INSTANCE;

		@Override
		public void request(long demand) {
		}

		@Override
		public void cancel() {
		}

	}

}
