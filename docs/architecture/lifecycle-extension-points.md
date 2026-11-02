# Agent Lifecycle Extension Points and Typed Plugin Contracts

Status: proposed. Contract for
[#298](https://github.com/agentic-ai-java/argi/issues/298).

This document is the contract decision for the agent lifecycle extension points. It
defines the catalog of stages, the plugin kinds that may attach to them, the identity
vocabulary the plugins observe, and the migration decision for the existing hooks and
interceptors. The executable part of the contract lives in the new
`io.github.agentic.ai.agent.lifecycle` package of `argi-agent-api`.

## Scope and boundaries

This issue owns the contract and the migration decision. It does not own the chain
executor, the declarative registration model or the engine wiring:

| Concern | Owner |
| --- | --- |
| Chain executor that invokes extensions per stage | #299 |
| Declarative registration and scoping of extensions | #300 |
| Engine wiring that binds extensions to a stage | #301 |
| General run control, budget and cancellation | #200 |
| State boundary and state ownership | #203 |
| Registration, scope and resource lifecycle | #202 |

The contract is engine-neutral by construction. `argi-agent-api` depends on `argi-api`
and `argi-runtime-api` only, so the types added here cannot depend on
`argi-workflow-graph-core`, on the loop runtime or on any provider SDK. A public
signature that reaches into the graph module is a defect, not a shortcut: the existing
`Hook` interface is exactly that case and is recorded in the migration table.

Two boundaries are deliberate and are not negotiable per engine:

- A workflow step is not a model turn. A workflow engine must not fabricate model
  turns to satisfy the catalog, because the model turn identity is what plugins use to
  correlate retries, budgets and per-turn accounting.
- A remote agent is not an in-process tool caller. The contract only exposes the
  public run-level stages of a remote agent. It never claims that a local plugin can
  intercept a tool call that executes inside the remote agent.

## Extension point catalog

The catalog is closed on purpose. Adding a stage means adding it here with its scope
and its capabilities, not adding an ad-hoc hook to one engine.

| Stage | Scope | Neutral carrier | Sync | Async | Cancellable | Streaming | Resumable | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Definition validation and creation | common runtime | catalog only | yes | no | no | no | no | Validation failures are reported to the caller, not intercepted. |
| Run admission | common runtime | catalog only | yes | no | no | no | no | The gate kind applies here: admission is a refusal decision. |
| Run start | common runtime | catalog only | yes | no | no | no | no | Observers may be attached to audit the start of a run. |
| Context build | common runtime | catalog only | yes | no | no | no | no | The context is rebuilt per attempt; the identity is stable. |
| Model call | common runtime | `ModelCallInvocation`, `ModelCallOutcome`, `ModelCallExecutor` | yes | yes | yes | no | no | The typed carrier exists; the executor is the chain continuation. |
| Model stream | common runtime | catalog only | no | yes | yes | yes | no | The streamed carrier is intentionally not added yet. |
| Action decision | common runtime | catalog only | yes | no | no | no | no | The decision itself stays engine-owned; only the observation is neutral. |
| Tool call | common runtime | `ToolCallInvocation`, `ToolCallOutcome`, `ToolCallExecutor` | yes | yes | yes | no | no | The typed carrier exists; the executor is the chain continuation. |
| Delegation, handover and join | common runtime | catalog only | yes | yes | no | no | no | The delegated agent runs its own invocation with its own identity. |
| Workflow step | engine specific | catalog only | yes | yes | yes | no | yes | Only a workflow engine owns this stage; it does not create model turns. |
| Suspend and resume | engine specific | catalog only | no | yes | no | no | yes | The owning engine defines the resume protocol. |
| Output delivery | common runtime | catalog only | yes | yes | no | no | no | Delivery to the caller, not the production of the answer. |
| Termination | common runtime | catalog only | yes | no | no | no | no | Completion, failure, cancellation and release share one stage. |

"catalog only" means the stage is part of the contract and may be observed, but the
neutral layer does not yet carry a typed request or response for it. An engine that
implements such a stage must state the mapping; it must not invent a second, engine
specific contract with the same name.

## Plugin kinds

Four kinds exist, and the catalog does not add a fifth. The kind of a plugin decides
what it is allowed to do, and the contract enforces the difference.

| Kind | Interface | Input | Return | May modify | Rejection and failure semantics |
| --- | --- | --- | --- | --- | --- |
| Observer | `LifecycleObserver` | `LifecycleEvent` | nothing | nothing | Cannot reject. A failing observer is reported as `LifecycleEvent.ExtensionFailed` and the run continues. |
| Restricted transformer | `LifecycleTransformer` | `LifecycleEvent` | `Optional<LifecycleEvent>` | Only the fields the event declares as mutable | Cannot reject and cannot short-circuit. A rewrite outside the mutable boundary is rejected by `LifecycleMutationGuard`, reported as an extension failure and the original event is kept. |
| Around interceptor | `AroundInterceptor` | `LifecycleEvent`, `AroundChain` | `LifecycleEvent` | The event of its bound stage | May short-circuit by not calling the chain and may call the chain more than once. A failure aborts the stage and is reported as an extension failure. |
| Admission gate | `AdmissionGate` | `LifecycleEvent` | `GateDecision` | nothing | The only kind that may refuse work. `DENY` short-circuits the stage with a reason; `REQUIRE_APPROVAL` must degrade to a denial on an engine without an approval channel, never to an allow. |

The four kinds compose instead of overlapping:

- A transformer that wants to refuse work needs a gate.
- A gate that wants to change a request needs a transformer.
- An interceptor that wants to refuse work needs a gate; the interceptor itself only
  short-circuits by producing a result instead of calling the chain.

The transformation boundary is deliberately narrow:

- a model call may replace its request, but not its model turn identity;
- a tool call may replace its arguments, but not its tool name and not its tool call
  identity, because renaming a tool inside a transformation would silently redirect
  the call to a different binding, which is an admission decision;
- every other event type is immutable.

## Identity vocabulary

The plugins need to correlate their own work with the work of the runtime. A single
opaque identifier cannot express that, so the contract names six identities instead.

| Identity | Type | Generated by | Lifetime | Nullable |
| --- | --- | --- | --- | --- |
| Invocation | `InvocationId` | The engine, once per call into an agent | The whole run | no |
| Attempt | `AttemptId` | The engine, once per retry of a unit of work | One attempt | yes |
| Step | `StepId` | The engine, once per unit of work | The unit of work, stable across retries | yes |
| Model turn | `ModelTurnId` | The engine, once per model interaction | One model interaction | yes |
| Tool call | `ToolCallId` | The engine, once per tool interaction | One tool interaction | yes |
| Delegation | `DelegationId` | The engine, once per hand-over to another agent | The hand-over and its join | yes |

`LifecycleIdentity` aggregates them. The invocation identity is always present; the
others exist only when the bound stage produces them.

The message identity of `AgentEvent.messageId` is deliberately not part of this
vocabulary. A message identity names a message; a model turn identity names the model
interaction that produced it. Mixing them would make per-turn accounting impossible.

The delegated agent is not represented by the delegation identity either. A delegated
agent runs its own invocation with its own invocation identity; the delegation
identity names the hand-over between them.

## Read-only runtime control

A plugin may read the trusted execution context of the run, and it may read nothing
else that could widen its own authority:

| Readable | Not writable by a plugin |
| --- | --- |
| Tenant, principal, run and correlation identifiers of the trusted context | The tenant or the principal of the run |
| The deadline of the run | The deadline, to extend the run |
| The delegation scope of the run | The delegation scope, to widen the scope |
| The current budget consumption | The budget, to raise the limit |

The trusted execution context is a read-only projection in the contract. There is no
mutator, no builder and no setter for it on any of the types added here, so a plugin
cannot rewrite a trusted identity even by accident. A plugin that needs a stronger
identity has to ask the runtime for a new one.

## Existing hooks and interceptors: keep, adapt, migrate

The existing extension points are not replaced. The decision per type is recorded
here so that the follow-up issues can be executed without re-opening the question.
"Keep" means the type stays exactly as it is, "adapt" means the type stays and gains
an adapter into the neutral contract, and "migrate" means the type will move into the
neutral layer in a later change with a deprecation period.

| Existing type | Module | Decision | Reason | Path |
| --- | --- | --- | --- | --- |
| `ModelInterceptor` | `argi-agent-api` | adapt | Already a neutral around interceptor for the model call stage; it only lacks the typed invocation and outcome of the new contract | An adapter maps `ModelRequest`/`ModelResponse` onto `ModelCallInvocation`/`ModelCallOutcome`; the signature stays |
| `Interceptor` | `argi-agent-api` | keep | The name contract is still the smallest common denominator of an extension | The neutral kinds carry their own identifier, the base interface stays as it is |
| `ModelCallHandler` | `argi-agent-api` | adapt | The chain continuation of the model call stage | Mapped onto `ModelCallExecutor` by an adapter, no signature change |
| `ToolAuthorizationPolicy` | `argi-agent-api` | adapt | It is an admission decision, which is exactly what a gate is | The policy becomes a decision source of an `AdmissionGate`; its signature stays |
| `ToolInterceptor` | `argi-agent-framework` | migrate | The neutral layer has no tool call interception point without it, but the type lives in the module that depends on the graph runtime | A neutral `ToolCallInterceptor` will be added to `argi-agent-api` and the framework type will delegate to it; the framework signature stays in place |
| `ToolCallRequest` | `argi-agent-framework` | migrate | The neutral `ToolCallInvocation` carries the same facts without the untyped context map | The framework type will be built from the neutral invocation |
| `ToolCallHandler` | `argi-agent-framework` | migrate | The neutral `ToolCallExecutor` is the same continuation | The framework type will delegate to the neutral executor |
| `ToolCallResponse` | `argi-agent-framework` | migrate | The neutral `ToolCallOutcome` separates a result from an error instead of overloading one field | The framework type will be built from the neutral outcome |
| `StreamingModelInterceptor` | `argi-agent-framework` | migrate | Streaming is a stage of the catalog, but the neutral streamed carrier is not added yet | The stream carrier is added together with the engine wiring, not in the contract issue |
| `Hook` | `argi-agent-framework` | migrate | It imports `io.github.agentic.ai.workflow.graph.KeyStrategy` and holds the agent and the state, so it cannot stay in a public neutral signature | The position concept becomes `LifecycleStage`; the graph dependency is dropped in the neutral type |
| `AgentHook` | `argi-agent-framework` | migrate | It holds `ReactAgent` and the overall state, which are engine objects | The neutral hook observes a stage instead of holding the agent |
| `HookPosition` | `argi-agent-framework` | migrate | `BEFORE_AGENT`, `AFTER_AGENT`, `BEFORE_MODEL` and `AFTER_MODEL` are a subset of the catalog | Mapped onto `LifecycleStage` values |
| `ModelHook` | `argi-agent-framework` | migrate | Same reason as `AgentHook` | Mapped onto an observer or an around interceptor of the model call stage |

Two rules apply to every row of this table:

- A public signature must not depend on the graph module or on the loop runtime. The
  neutral types depend on `argi-api` and `argi-runtime-api` only.
- A type is not renamed or rewritten without a real benefit. The existing
  interceptors keep working; the neutral contract is added next to them and the
  migration happens in the follow-up issues.

## Contract example and verification

The contract is verified without a model, a tool binding and an engine. The test
`LifecycleContractTest` in `argi-agent-api` drives the four kinds directly and pins
down five paths:

1. observation of an immutable, typed event;
2. a transformation inside the mutable boundary;
3. a rejection that short-circuits the stage;
4. a wrapping that rewrites, short-circuits and repeats the chain;
5. the failure semantics of a failing observer and of a failing interceptor.

The test also asserts the dependency boundary: the classpath of `argi-agent-api`
contains no type of the graph module, and no class of the neutral package references
the graph or the loop module.

## Breaking change assessment

| Question | Answer |
| --- | --- |
| Smaller option | A documentation-only change without the neutral package |
| Why not smaller | The catalog would stay unenforceable: the tool call stage would still have no neutral interception point, and the identity vocabulary would remain implicit. The follow-up issues could not be implemented against a document alone |
| Benefit | One contract for every engine, an executable transformation boundary, and a dependency direction that keeps the graph runtime out of public signatures |
| Impact | Additive only. No existing signature changes, no module dependency changes, no configuration or persistence format changes |
| Target version | The current development line, next minor release |
| Migration | None required. Existing hooks and interceptors keep working; the adapters and the framework side migration are follow-up work in #299, #300 and #301 |

The breaking part of this work is the *future* migration of the framework types.
When `ToolInterceptor` and `Hook` move, the smaller option is a neutral type plus an
adapter in the framework module, so that no consumer has to change its imports in one
step. That migration is explicitly out of scope here.
