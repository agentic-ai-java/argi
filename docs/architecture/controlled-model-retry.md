# Controlled model retry

This document describes the retry contract that the agent runtime uses to repeat a
model call, and the configuration that keeps a retry sequence bounded.

## Why the retry used to be uncontrolled

`ModelRetryInterceptor` repeated a failed model call with three properties that made
the sequence hard to reason about:

- **The retryability of a failure was guessed from its text.** The default predicate
  matched substrings such as `connection`, `timeout` or `i/o error`, so a permanent
  rejection that happened to mention a timeout was repeated as well, while a transient
  failure with an unfamiliar message was not.
- **The run deadline was ignored.** The runtime owns a deadline for every run, but the
  retry loop started the next provider call unconditionally, even when the deadline of
  the run had already passed.
- **A cancelled run kept the sequence alive.** The loop observed thread interruption
  only, while a run cancellation is delivered through the execution guard of the run.
  A cancelled run therefore received another provider call after the cancellation.

## The contract

The neutral contract lives in `io.github.agentic.ai.agent.retry` in the `argi-agent-api`
module, so that the independent ReAct engine and the graph based framework share it
without either of them depending on the other.

| Type | Responsibility |
|---|---|
| `ModelCallFailureCategory` | The classification of a failure: `TRANSIENT`, `RATE_LIMITED`, `PERMANENT`, `CANCELLED`, `UNKNOWN`. |
| `ModelCallFailure` | A classified failure and its cause. |
| `ModelCallFailureClassifier` | Classifies a failure; implementations classify from the failure type and its cause chain. |
| `TypeBasedModelCallFailureClassifier` | The default classifier; it reads types only and never the text of a message. |
| `RetryPolicy` | The bounded configuration: attempts, backoff, retryable categories and the reserved deadline time. |
| `RetryStopReason` | Why a sequence stopped: `ATTEMPTS_EXHAUSTED`, `NON_RETRYABLE`, `DEADLINE_EXCEEDED`, `CANCELLED`, `EXECUTION_STOPPED`. |
| `RetryDecision` | The outcome of one decision: allowed, retry after a delay, or stopped with a reason. |
| `ModelRetryController` | The state machine of one model call; it owns the attempt counter and the deadline. |

`ModelRetryController` never waits. The caller owns the backoff and has to ask the
controller again before every attempt, because a cancellation or a deadline can stop
the run while the caller waits.

## The three axes of a retry sequence

1. **The number of attempts.** `RetryPolicy.maxAttempts` counts the first call, so
   `maxAttempts(3)` performs at most three provider calls.
2. **The retryability of the failure.** The classifier maps the failure to a category
   and the policy decides which categories may be repeated. `UNKNOWN` is not retryable
   by default: a failure that cannot be classified is not repeated.
3. **The remaining time.** The effective deadline comes from the execution guard of the
   run and falls back to the trusted execution context of the request. When the deadline
   has passed, when less than `deadlineReserve` is left, or when the next backoff would
   outlive the deadline, the sequence stops before another call is dispatched.

## The guard of the run

`ModelRequest` carries an optional `ExecutionGuard`. The engines propagate the guard
of the run they execute:

- the independent ReAct engine passes the control of its execution context;
- the graph based framework passes the guard of the run configuration.

The guard never reaches a provider: it is a typed field of the request and is not part
of the request attributes that the adapters forward to the model.

With a guard present, the interceptor waits for the backoff in slices and checks the
guard before every slice, so a cancelled or expired run ends the wait instead of
dispatching another call. A stopped run is reported with the reason of the guard
(`CANCELLED`, `DEADLINE_EXCEEDED` or `OWNERSHIP_LOST`) and is never reported as an
exhausted retry.

## Defaults and migration

The existing configuration keeps its behaviour. The retryable exception predicate stays
the default decision, so an application that configured the interceptor before stays
unchanged.

| Configuration | Decision | Recommended for |
|---|---|---|
| `.retryableExceptionPredicate(predicate)` (default) | The predicate decides, as before. | Existing applications. |
| `.failureClassifier(classifier)` | The classifier decides by failure type; the predicate is not consulted. | New applications, or applications that replace the predicate with a type based rule. |
| `.retryableCategories(categories)` | Restricts the categories that may be repeated. | Applications that want to repeat a category that is not retryable by default. |
| `.deadlineReserve(duration)` | The time that must remain before the deadline of the run to start another attempt. | Applications whose provider calls need room to report their result. |

The type based classifier is the recommended default:

```java
ModelRetryInterceptor.builder()
	.maxAttempts(3)
	.initialDelay(1000)
	.backoffMultiplier(2.0)
	.failureClassifier(new TypeBasedModelCallFailureClassifier())
	.deadlineReserve(Duration.ofMillis(500))
	.build();
```

A provider that reports its failures through its own exception types can supply a
dedicated classifier instead, which keeps the decision independent of the text of a
message.

## Scope

This contract covers the repetition of one model call. It does not switch models, it
does not replay tool calls, it does not own a persistent retry budget and it does not
change the boundary of a streaming response that already produced output. The
assembly of the retry behaviour as a lifecycle strategy, together with the other model
policies, belongs to the lifecycle chain and to the model policy work.
