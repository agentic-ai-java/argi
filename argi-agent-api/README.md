# ARGI Agent API

`io.github.agentic-ai-java:argi-agent-api` provides Java 17, engine-neutral
Agent invocation contracts. Its only production dependencies are `argi-api`
(messages, model and tool contracts) and `argi-runtime-api` (trusted identity,
budgets and execution control). It does not bring in Graph, Runtime Core,
Jackson, Reactor, Spring, provider SDKs or an execution engine.

This completes the contract extraction in [#195](https://github.com/agentic-ai-java/argi/issues/195),
not the independent ReAct engine (#196), declarative Workflow engine (#197),
Loop migration/optional bridge (#198), or definition factories (#199).
The existing Graph-backed agents continue to work through their existing API.

## Invocation contract

New types live in `io.github.agentic.ai.agent`:

| Type | Semantics |
| --- | --- |
| `Agent` | One cold `Flow.Publisher<AgentEvent>` per invocation; the same interface for local, Workflow and remote implementations |
| `AgentRequest` | Messages, business inputs and optional opaque resume token; not mutable execution state |
| `AgentOutcome` | Authoritative messages, business outputs and cumulative invocation usage; COMPLETED or SUSPENDED |
| `AgentEvent` | Message deltas/snapshots, progress, cumulative usage and exactly one Finished event |
| `AgentExecutionContext` | Caller-owned authenticated constraints and an ExecutionControl derived from those exact constraints |

There are no graph compilation methods, node conversions, merge strategies,
checkpoint savers or graph configuration in these signatures. Application maps
are shallow snapshots; applications must not mutate nested values during a call.
They are never an authentication source. Only the application's authenticated
boundary may construct the trusted context. Remote executors must reauthenticate
incoming requests; serializing this context does not establish remote trust.

A stream starts work on subscription, honors Flow demand and is intended for
one subscriber. It emits intermediate events, then Finished followed by
onComplete, or fails with onError. Finished is not followed by any other event.
The final outcome is authoritative: clients must not append it to accumulated
message deltas. Usage updates replace previous totals and are not additive.

`Agent.invoke` subscribes once, discards intermediate events without buffering,
and returns a CompletableFuture. It rejects empty completion, multiple outcomes,
events after an outcome, and stream/transport failures. It waits for onComplete
rather than hiding a late transport error behind an early Finished event.
Application/schema validation failures remain the responsibility of the executor;
they are not inferred from assistant text.

Cancelling the future cancels its subscription and context. Context cancellation,
ownership loss and deadline checks fail the future and cancel its subscription.
The caller closes the context; completion of one child does not close a scope
shared by a sequential caller. Independent calls need independent contexts.
Direct stream consumers must also honor the publisher's cancellation lifecycle.
Executors must propagate control loss to in-flight model/tool/transport operations
and check before side effects. Control is cooperative: there is no hidden scheduler,
interrupting thread, resource accounting or distributed fencing in this artifact.
An executor that requires deadlines during silence must arrange its own timer.

SUSPENDED requires a non-blank resume token plus application-visible prompts.
Resume uses the same AgentRequest, with feedback in inputs. Tokens are not
credentials or portable checkpoint JSON. The executor must authenticate the caller,
validate tenant/run/definition ownership, reject replay or stale tokens, and report
unsupported resume explicitly. Graph's InterruptionMetadata is not silently
translated into this token format. No resume storage or migration is implied.

## Existing-to-new mapping and ownership

| Existing capability / dependency evidence | Owner and decision |
| --- | --- |
| `graph.agent.Agent`: initGraph, CompiledGraph, RunnableConfig, OverAllState | Retain in Graph-backed Framework. New `agent.Agent` is an independent contract, not a facade delegating to that class. Implementations migrate under #196/#197; optional Loop bridging under #198. |
| `Optional<OverAllState>` / NodeOutput stream | New business request/outcome and ordered events. No implicit graph-state merge, node routing, checkpoint or serializer equivalence. |
| Existing `AgentResult` | Keep strict-output validation/delivery result in Framework. It is not the same abstraction as AgentOutcome; future engines can report validated business output without discarding validation failures. No automatic conversion is added. |
| `Interceptor`, `ModelRequest`, `ModelResponse`, `ModelCallHandler`, `ModelInterceptor` | Extract existing implementations into Agent API, with unchanged FQCNs and behavior. Model requests use neutral messages/tools and trusted Runtime API context. The old Framework now consumes these same classes. |
| `AuthorizationDecision` and `ToolAuthorizationPolicy` | Extract into Agent API with unchanged signatures. Policies are shared; executors invoke them before every tool attempt. The historical requireMatchingTenant policy only requires a tenant; target-tenant checks need an application policy. |
| ToolCallRequest / ToolCallExecutionContext / ToolAuthorizationInterceptor | Keep in Framework: ToolCallExecutionContext holds RunnableConfig and OverAllState. #196 must replace this lifecycle explicitly, not move a hidden graph dependency. Neutral Tool/ToolRegistry stay in argi-api. |
| InterceptorChain / StreamingModelInterceptor | Framework owns Reactor stream composition and graph lifecycle. A Flow model response is shared, but retry/stream terminal ordering is not claimed equivalent for future engines. |
| TrustedExecutionContext / ExecutionBudget / ExecutionControl | Runtime API owns identity, immutable limits, delegation, cancellation/deadline/ownership signals. Agent API reuses them. Usage enforcement and accounting remain executor duties. |
| ModelCallLimitHook / ToolCallLimitHook | Framework-specific counters in OverAllState and RunnableConfig. #196 supplies independent enforcement; copying these hooks would not remove graph state. |
| InterruptionHook / HumanInTheLoopHook | Framework-specific state and InterruptionMetadata. Shared suspension boundary is new; durable resume, token validation and effect replay remain engine/storage responsibilities. |
| SummarizationHook / message history / checkpoint memory | Framework currently reads message history/config and graph checkpoints. Business messages are shared, but compaction policy, durable history and checkpoint semantics are not one universal state map. |
| Graph observation and model/tool/run observation | Graph scheduling observations remain optional Graph integration. Engines may observe the shared events/control through lightweight integrations; no required metrics backend or instrumentation dependency is added. |
| Run/Task stores, definition references, version selection | Runtime API/Core and #199/#200, not Agent API. No duplicate run registry or resolver is introduced. |

## Alternatives and migration

- Extending the old Agent or adding a default Graph delegate would keep hidden
  compilation/scheduling dependencies. Rejected.
- Putting execution contracts and stores into argi-api would couple every model
  adapter to the whole runtime. Rejected; Agent API is a separate opt-in artifact.
- Renaming all shared interceptor classes at once would break source/binary
  consumers without improving dependency isolation. Instead, seven existing
  classes move without wrappers or duplicate implementations. Their historical
  `graph.agent.interceptor` namespace is transitional, not a Graph artifact dependency.
- Returning OverAllState under a different name would retain graph merge and
  recovery semantics. New business contracts deliberately do not preserve those
  semantics. There is no automatic state/checkpoint conversion.

Maven consumers of argi-agent-framework receive Agent API transitively; existing
imports, configuration and stored payloads are unchanged. New consumers depend
on argi-agent-api using the matching argi-bom (initial target: 2.1.0-RC2-SNAPSHOT).
Manual classpaths must add the new jar and replace the old Framework jar together;
do not mix an older Framework jar containing the extracted classes with Agent API.
The extraction is source/binary compatible on the classpath; split historical
packages do not promise JPMS module-path compatibility. Final package migration
must coordinate with #182/#198 rather than duplicate types with wrappers.

Rollback the matching Framework/Agent API/BOM set together. This extraction writes
no new persisted state and requires no data migration. Adopting the new invocation
API is explicit, not an automatic replacement for existing Graph calls.

## Verification

```sh
./mvnw -B -pl :argi-agent-api -am test
tools/scripts/verify-api-contract.sh
```

The independent consumer compiles and executes local model interception, a real
loopback HTTP call, and a two-step composition through the same Agent interface.
Its classpath contains exactly Agent API, Runtime API and API. The gate checks
public signatures, actual bytecode dependencies (java.base only), missing types,
and duplicate classes in Framework. These are executable contract consumers,
not production ReAct, declarative Workflow or remote-protocol engines.

Unit tests cover immutable boundaries, suspended outcomes, malformed streams,
cancellation in both directions, late subscriptions, deadline and ownership loss,
completion races, and 100,000 intermediate events without collector accumulation.
Existing Framework/Studio tests remain necessary for the extracted capabilities'
legacy behavior. Real database takeover, multi-JVM leases, model providers and
production capacity are not validated by this API-only consumer.
