# Model fallback

The primary client is the one configured on the agent. Alternative clients are
named in dispatch order. For example, bind the following application configuration
to `io.github.agentic.ai.agent.fallback.ModelFallbackConfig`:

```json
{
  "candidates": ["regional", "local"],
  "policy": {
    "switchableCategories": ["TRANSIENT", "RATE_LIMITED"],
    "maxSwitches": 2,
    "maxTotalAttempts": 3
  }
}
```

Supply already constructed clients during assembly. An unresolved name fails at
startup. The configuration uses the application's configuration reader; ARGI does
not require a Spring context or create provider clients.

```java
ModelFallbackConfig config = mapper.readValue(json, ModelFallbackConfig.class);
var fallback = config.bind(Map.of("local", localClient, "regional", regionalClient));
var agent = ReActAgent.builder(primary).interceptors(List.of(fallback)).build();
```

The same interceptor can be passed to the graph-based `ReactAgent` builder.
These builders are the registration points on the current branch. The generic
lifecycle registry and engine adapters are tracked in #299, #300 and #301.
The default classifier recognizes cancellation and transient I/O failures by
type. To identify a provider's rate-limit exceptions, supply its classifier with
`ModelFallbackInterceptor.builder().failureClassifier(classifier).policy(policy)`
and add the alternative clients in order.

## Limits and failures

With `fallback` outside `retry`, a retryable failure retries the current client.
A failure eligible only for switching advances immediately. A switch uses one
attempt. If the retry limit is two and A has already been called twice, B receives
zero calls even if both the switch limit and the list would allow B. If A fails
once and switches to B, B can use the one remaining attempt. Each independent
stream subscription starts from the same initial position. Cancellation, a stopped
execution guard, or an error following any public stream event ends the sequence.

On exhaustion inspect `ModelFallbackException.reason()`, `attempts()`,
`switches()`, and `getCause()`. A disallowed category stops with
`NON_SWITCHABLE`. Cancellation retains the original cancellation exception.
Text in a successful response never triggers a switch.

## Migrating existing builders

Existing `addFallbackClient` and `fallbackClients` calls keep their ordering and
switch on any non-cancellation category until the alternative list ends. The
class keeps its Java package; the implementation is now supplied by
`argi-agent-core`, which the framework already depends on. This also makes it
available to independent agents without importing Graph. The shared attempt cap
replaces the previous per-client multiplication when retry and fallback are
combined. Set both total limits to the maximum number of provider calls the
whole turn is allowed to make.
