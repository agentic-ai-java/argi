# ARGI Graph

## Runtime contract extraction

Identity, definition and execution-control contracts are provided by
[`argi-runtime-api`](../argi-runtime-api/README.md), which is JDK-only.
Run/Task data, codecs, definition admission and reference storage are provided by
[`argi-runtime-core`](../argi-runtime-core/README.md), which depends only on Runtime
API and Jackson. Graph supplies both dependencies transitively. Existing class
names and v1 data remain readable; neutral v2 adoption and rollback are documented
in Runtime Core. Graph tracing remains an optional integration for runtime-only
consumers. Independent Agent/Workflow execution and the Loop migration remain separate.

## What's ARGI Graph

ARGI Graph is a **workflow and multi-agent framework** for Java developers to build complex applications composed of multiple AI models or steps.

ARGI Graph serves as the underlying core engine of the Agent Framework. It provides atomic components for building intelligent agents with interruptible and orchestratable capabilities, offering high flexibility but also relatively high learning costs. In contrast, the Agent Framework is built atop Graph, abstracting away the underlying complexities through concepts like ReactAgent and SequentialAgent.

Please check [the documentation](https://java2ai.com/docs/frameworks/graph-core/quick-start) on official website for more details

## Core Concepts & Classes

Graph is deeply integrated with the Spring Boot ecosystem, providing a declarative API to orchestrate workflows. This allows developers to abstract each step of an AI application as a node (Node) and connect these nodes in the form of a directed graph (Graph) to create a customizable execution flow. Compared to traditional single-agent (one-turn Q&A) solutions, ARGI Graph supports more complex multi-step task flows, helping to address the issue of a **single large model being insufficient for complex tasks**.

The core of the framework includes: **StateGraph** (the state graph for defining nodes and edges), **Node** (node, encapsulating a specific operation or model call), **Edge** (edge, representing transitions between nodes), and **OverAllState** (global state, carrying shared data throughout the flow). These designs make it convenient to manage state and control flow in the workflow.

1. StateGraph
   The main class for defining a workflow.
   Lets you add nodes (addNode) and edges (addEdge, addConditionalEdges).
   Supports conditional routing, subgraphs, and validation.
   Can be compiled into a CompiledGraph for execution.
2. Node
   Represents a single step in the workflow (e.g., a model call, a data transformation).
   Nodes can be asynchronous and can encapsulate LLM calls or custom logic.
3. Edge
   Represents transitions between nodes.
   Can be conditional, with logic to determine the next node based on the current state.
4. OverAllState
   A serializable, central state object that holds all workflow data.
   Supports key-based strategies for merging/updating state.
   Used for checkpointing, resuming, and passing data between nodes.
5. CompiledGraph
   The executable form of a StateGraph.
   Handles the actual execution, state transitions, and streaming of results.
   Supports interruption, parallel nodes, and checkpointing.
6. InterruptableAction
   Interface for actions that can interrupt graph execution.
   Provides two hook points: `interrupt()` (before execution) and `interruptAfter()` (after execution).
   Useful for human-in-the-loop scenarios, approval workflows, and multi-turn conversations.

## How It's Used (Typical Flow)
- Define StateGraph: In a Spring configuration, you define a StateGraph bean, add nodes (each encapsulating a model call or logic), and connect them with edges.
- Configure State: Use an OverAllStateFactory to define the initial state and key strategies.
- Execution: The graph is compiled and executed, with state flowing through nodes and edges, and conditional logic determining the path.
- Integration: Typically exposed via a REST controller or service in a Spring Boot app.

## Concurrent Calls and Checkpoints

Graph and ReactAgent executions using the same checkpoint saver instance and checkpoint
namespace run in FIFO subscription order within one JVM. A queued call loads the latest
checkpoint only after the preceding call finishes, fails, or completes cancellation
cleanup. Cancelling a waiting call removes it without running its nodes or changing
checkpoint state. ReactAgent cancellation also finishes its checkpoint rewind before the
next call starts.

The namespace comes from `BaseCheckpointSaver.checkpointThreadId(config)`, including
application and user metadata when present. Calls for different namespaces or different
saver instances can still run concurrently. An omitted `threadId` uses the shared
`$default` namespace; set explicit thread IDs to isolate conversations. Graphs configured
with an empty `SaverConfig` keep their previous concurrent behavior.

### Missing threadId semantics

An omitted `threadId` is shared state, not a private bucket. Every caller without a
`threadId` on the same saver instance reads and writes the same `$default` checkpoint
history; when `app_name`/`user_id` metadata is present the bucket is scoped per
app/user, but conversations of that identity without a threadId still share one
history. This fallback is logged as a warning (once per JVM). For persistence-backed
or multi-user deployments, enable the strict policy with the
`argi.checkpoint.requireExplicitThreadId=true` system property or
`CheckpointThreadIdPolicy.setRequireExplicitThreadId(true)`; checkpoint operations
then fail fast with `IllegalArgumentException` until the caller supplies an explicit
`threadId`.

This is an in-process execution queue, not a distributed lock. Separate saver instances
or application processes pointing at the same database need external coordination.
Direct saver writes and explicit `CompiledGraph.updateState` calls are outside this
queue; applications must coordinate these with running executions. A queued call keeps
waiting while its predecessor remains active, so apply an application timeout or cancel
the subscription when that wait is no longer wanted.

## Opt-in Run and Task Storage Contracts

`io.github.agentic.ai.graph.execution.run` provides backend-neutral admission and
atomic storage contracts for recoverable run/task **data**. It does not schedule a
worker, replace `TaskRepository`/its local `Supplier` API, change a checkpoint saver,
or enable distributed execution automatically. Existing APIs, defaults, checkpoint
and Store formats, and `argi.*` configuration remain unchanged. No migration is
required for existing applications.

### Data and authentication boundary

- `RunSpec` schema version 1 stores the graph id/version, run id, submission key,
  canonical input hash, input data and a `TrustedExecutionContextReference`.
  Registered graph/task identifiers resolve code at the application boundary;
  they are not serialized closures. `TaskSpec` stores task id, parent run id,
  registered task type/version, input and a parent or narrowed child context.
- `RunNamespace` is a structured `(tenant authority/id, applicationId, sessionId)`
  key. The run id is deliberately excluded: different runs of one session share
  a reservation, physical owner and monotonically increasing fence.
- Create a namespace/spec from a live `TrustedExecutionContext` at the application's
  authentication boundary, then pass that context explicitly to `RunStore.submit`.
  The context must exactly match the spec's reference. A decoded reference, model
  argument, ordinary metadata or JSON namespace is **not** authentication.
  Applications authorize session/application access, store reads and worker access;
  workers re-resolve persisted identities before recovery and side effects.
- Deadline (epoch milliseconds), budget limits and delegation permissions are
  persisted in the context reference. Task identity, correlation, permissions,
  deadline and budget cannot exceed the parent run. Deadline rounding is
  conservative: admission uses the persisted millisecond deadline.
  Model/tool deadline checks and consumption accounting belong to the runtime;
  storing a budget is not evidence that it has been enforced.
- `RunPayload` accepts only JSON object text, a data-schema type and a positive
  payload version, never an arbitrary Java object. It canonicalizes nested object
  keys while preserving array order. SHA-256 covers payload schema, type and JSON;
  a forged input hash is rejected. Numbers follow Jackson's normalized JSON
  representation, not cross-language semantic equality.
  `RunSpecCodec`/task codecs reject unsupported envelope versions, duplicate keys,
  missing/unknown fields, scalar coercion and trailing documents; they never enable
  polymorphic Java typing. Application payload versions evolve separately from
  envelope version 1. Applications must redact tokens, credentials and other
  secrets before admission; a JSON data API cannot recognize arbitrary secrets.

### Admission, ownership and atomic writes

`submit` atomically deduplicates `(namespace, submissionKey)` and reserves the
session for a new `PENDING` run. Same-key submissions with the same canonical input,
graph/version and principal return the original snapshot, even with a different run
id or after completion. The original deadline/budget/permissions are retained; a
retry does not update constraints. Changed input/graph/principal conflicts. Different
keys/runs conflict while a nonterminal run reserves the session. Reusing an existing
run id for another key also conflicts. Failed admission leaves no record.

The logical active-run reservation has **no lease TTL**. `WAITING` retains it.
Physical `RunLease` ownership expires or can be released independently; neither
action frees the logical session. Only the reserved run may acquire a namespace
lease. Every acquisition increments a namespace fence, including after a terminal
run and subsequent run. Renew/release cannot affect a newer owner/fence. The store's
clock and stored expiry are authoritative, never a caller-supplied expiry claim.

`RunCommit` requires the live namespace owner/fence and exact run revision. In one
atomic boundary it validates and publishes run state, checkpoint, task states,
journal and outbox. Revision starts at 0 and increments per committed write. Task
updates have independent CAS revisions; `-1` creates a `PENDING` task at revision 0.
Task specs are immutable and duplicate task updates in one command are rejected.
A null checkpoint retains the previous checkpoint. Terminal commits require all
tasks to be terminal and release reservation plus physical lease atomically.
Invalid transitions, stale revisions/owners, invalid task data or append conflicts
must leave **every** component unchanged. At/after a stored deadline, only
`TIMED_OUT` or `CANCELLED` writes are accepted for that run/task, including a task
whose narrower deadline expires while its parent run is still live.

| Current state | Allowed next states |
| --- | --- |
| `PENDING` | `PENDING`, `RUNNING`, `CANCELLED`, `TIMED_OUT` |
| `RUNNING` | `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT` |
| `WAITING` | `WAITING`, `RUNNING`, `FAILED`, `CANCELLED`, `TIMED_OUT` |
| Terminal | None, including same-state writes |

`RunEntry` supplies versioned, append-only `MODEL_CALL`, `TASK_CALL`, `APPROVAL`
and `EVENT` boundaries. An existing entry id with identical data is idempotent;
different data conflicts. Outbox accepts `EVENT` only. Entries and checkpoints use
independently versioned application payloads. An atomic outbox persists intent,
not proof of delivery or exactly-once provider execution. Entries remain readable
after completion; transport, delivery cursors, retries and consumer deduplication
belong to an optional runtime/backend. No durability or delivery methods are empty
default implementations.

### Backend obligations and reference validation

`RunStoreCapabilities.requireDurableMode()` is a fail-closed opt-in gate. Durable
mode requires **all** of: restart persistence, atomic run/checkpoint/outbox writes,
namespace fencing, session reservations, submission deduplication and revision CAS.
Implementing `RunStore`, a lease alone, or a versioned checkpoint saver alone is
insufficient. An adapter must honor its advertised capabilities; independent writes
to a saver and an event store do not constitute an atomic commit.

`MemoryRunStore` implements the contract with immutable snapshots and a shared
monitor. Validation builds all replacement data before publishing it. It is a
single-process, unbounded reference implementation, **not durable storage**:
all guarantees require sharing one instance, all data is lost on restart, and its
durable-mode gate always rejects. It neither provides cross-JVM coordination nor
enables worker execution. Persistent adapters belong in Extensions; production
activation remains subject to the existing P0 safety gates. This contract does not
promise Redis HA RPO0, general exactly-once execution, MQ or node sharding.

`RunStoreContractTest` is a reusable abstract reference suite for adapters (reuse
the fixture source in backend suites; no test-fixture artifact is published). It
checks deduplication, lifecycle, reservations independent of TTL, owner/fence
takeover, task CAS, immutable reads, late-failure rollback, deadline boundaries and
concurrent submission/owner/revision races. Backend tests must additionally prove
real transaction rollback, restart persistence and independent-JVM takeover using
the actual storage service. The in-memory suite is not evidence of those properties.

```shell
./mvnw -B -pl :argi-graph-core -am \
  -Dtest=MemoryRunStoreTest,RunDataContractTest test
```

## Version-Pinned Run Definitions and Recovery Capability

`io.github.agentic.ai.graph.execution.definition` adds the second half of the
opt-in recovery story: recovery must load the graph, tool catalog/schema, codec and
application definition versions the run was admitted with, and it must never
silently fall back to the currently registered latest version. `RunSpec` already
carries `graphId`/`graphVersion`; this package adds the resolver, the registration
and the worker capability check around it.

### Opt-in boundary

Nothing here is required by the existing run, task or checkpoint APIs:

- `RunSpec`, `TaskSpec`, `RunStore`, the checkpoint savers and every `argi.*`
  setting keep their current shape and defaults. `RunSpec` still has schema version
  1 and the same JSON codec; no existing caller needs a registry.
- A `RunDefinitionRecord` is recorded only when an application opts in, and only
  `RunDefinitionAdmission.resolve` reads it. `RunStore.submit` never consults it.
- Definitions are data identifiers. `DefinitionResolver` returns framework-neutral
  objects, so `argi-graph-core` gains no model SDK or Extensions dependency, and the
  neutral-API consumer gate compiles the contract without either.

### Types

| Type | Role |
| --- | --- |
| `DefinitionKind` | The four pinned domains: `graph`, `tool_catalog`, `codec`, `application`. |
| `DefinitionReference` | `(kind, id, version)`, e.g. `graph[checkout@1]`. |
| `RunDefinitionRecord` | The four references committed with a run. `forRun` derives the graph reference from the run spec itself, so it cannot drift. |
| `DefinitionResolver` / `DefinitionRegistry` | Application-owned resolution. The registry retains every version an application wants to keep recoverable and rejects duplicate registration, so a retained version can never be overwritten. |
| `WorkerCapabilities` | What a worker build implements: the highest envelope version it reads, the definition kinds it supports and the data codecs it can execute. |
| `RunDefinitionAdmission` | The single admission point: resolve the record, or reject it before anything executes. |

### Fail-before-tool semantics

`RunDefinitionAdmission.resolve` verifies the envelope version, the worker's kind
support, every recorded version and finally the data codec, and it returns a
complete `ResolvedRunDefinitions` only when all of them succeed. A worker therefore
cannot dispatch a tool with a partially resolved or newer definition: the failure
happens before the definitions are handed over.

| Failure | Structured error | Code |
| --- | --- | --- |
| Recorded version not retained | `DefinitionResolutionException` | `NOT_REGISTERED` |
| Worker lacks the kind | `DefinitionResolutionException` | `KIND_UNSUPPORTED` |
| Registered object is the wrong type | `DefinitionResolutionException` | `TYPE_MISMATCH` |
| Record pins a different graph than the run | `DefinitionResolutionException` | `RECORD_MISMATCH` |
| Envelope version unreadable | `CodecIncompatibilityException` | `ENVELOPE_VERSION_UNSUPPORTED` |
| Recorded data codec unsupported | `CodecIncompatibilityException` | `CODEC_VERSION_UNSUPPORTED` |
| Component cannot be rebuilt from data | `RecoveryCapabilityException` | `COMPONENT_NOT_RECOVERABLE` |

Branch on `code()` and on `reference()`, `recorded()` or `supported()`; the message
is for humans only.

### Definition retention and rollback window

A definition version may be unregistered only when no non-terminal run still pins
it. Treat the two windows as operational constraints:

- **Retention window**: keep every version referenced by a non-terminal run, and
  for a terminal run keep it for at least the period in which its journal, outbox
  and checkpoint may still be replayed. A practical default is to retain the
  current version plus every version still referenced by a non-terminal run, and to
  retain the previous version for one full release cycle after the last run pinned
  to it reaches a terminal state.
- **Rollback window**: rolling an application back requires the older definition
  version to still be registered, because recovery resolves the recorded version
  rather than the rebuilt one. Deploy the new definition version additively, keep
  the previous version registered for the length of the rollback window, and only
  then drop it. Removing a version early turns future recovery of those runs into a
  `NOT_REGISTERED` failure instead of a silent upgrade.

`DefinitionRegistry.references()` lists the retained set for audit and for a
retention check in application startup.

### Durable mode requires recoverable components

`RunDefinitionAdmission.requireDurableMode` composes the existing fail-closed store
gate (`RunStoreCapabilities.requireDurableMode`) with an explicit recovery
declaration for every `RuntimeComponentRole`: `CUSTOM_NODE`, `CHAT_MEMORY`,
`STORE` and `BACKGROUND_TOOL`. A component that declares `INSTANCE_BOUND` — an
in-JVM cache, a callback, a `Future`, a `Supplier`-backed task repository — rejects
durable mode. So does a role that was never declared: an undeclared role means the
capability was not established, and a role the application does not use is declared
`NOT_APPLICABLE` on purpose.

### Reference validation

```shell
./mvnw -B -pl :argi-graph-core -am \
  -Dtest='io.github.agentic.ai.graph.execution.definition.*Test' test
```

`JvmRestartRecoveryTest` starts a second JVM that receives only the committed
record file and rebuilds the run there, so restart recovery is verified as data,
not as in-memory state. `RunDefinitionOptInBoundaryTest` asserts that the existing
contracts carry no definition type and that no recovery entry point accepts an
instance handle.

## Cross-Agent Trace Correlation

`io.github.agentic.ai.graph.observation.trace` turns the existing Micrometer
`ObservationRegistry` into a searchable chain across agent boundaries: delegation,
acceptance, model/tool attempts and recovery stay connected instead of surfacing only
as an isolated child failure. It is an explicit opt-in — constructing an `AgentTracer`
and passing it to the integration points listed below — and with `ObservationRegistry.NOOP`
(or no tracer at all) every code path behaves exactly as before and emits nothing.
Installing the observation starter alone does not enable it.

### Operations and correlation keys

| Operation | Name | Role | Purpose |
| --------- | ---- | ---- | ------- |
| `AGENT_DELEGATION` | `argi.agent.delegation` | producer | Wraps a sub-agent invocation (TaskTool) |
| `A2A_SEND` | `argi.agent.a2a.send` | producer | Wraps the A2A HTTP request (also on worker threads) |
| `A2A_RECEIVE` | `argi.agent.a2a.receive` | consumer | Wraps response parsing (blocking and streaming) |
| `TASK_RECEIVE` | `argi.agent.task.receive` | consumer | Wraps background task result retrieval |
| `RUN_STORE` | `argi.run.store` | producer | Wraps `RunStore.submit/acquire/commit` via `ObservedRunStore` |

Low-cardinality keys (tag-safe): `argi.agent.operation`, `argi.agent.role`,
`argi.agent.phase`, `argi.agent.success`, `argi.agent.outcome`, `argi.agent.error.type`.
High-cardinality keys (identify, do not use as tags): `argi.agent.tenant`,
`argi.agent.run.id`, `argi.agent.task.id`, `argi.agent.attempt.id`,
`argi.agent.delegation.id`, `argi.agent.thread.id`, `argi.agent.error`.

### Identities, propagation and links

- `AgentTraceContext.capture(RunnableConfig, state)` reads the tenant, run id and
  thread id from the config (tenant/run from `TrustedExecutionContext`), and the
  attempt id from the graph execution id kept in the state; `ObservedRunStore` uses
  the `RunLease` fencing token as the attempt id.
- Each delegation/receive exchange carries its own `delegationId`. Producer and
  consumer spans share it, and request metadata on the wire carries
  `argiRunId`/`argiTenant`/`argiAttemptId`/`argiThreadId`/`argiDelegationId` so the
  remote side can correlate.
- Micrometer `Observation` (1.x) has no span-link API, so fan-in, long waits and
  recovery connections are modeled with the high-cardinality
  `argi.agent.link.delegation.id` key (`AgentTraceSpan.linkToDelegation`) rather than
  a parent/child scope. Consumer spans on worker threads never rely on thread-local
  propagation.
- While a span is open, tenant/run/task/attempt/delegation ids are mirrored to MDC
  (`argi.tenant`, `argi.agent.run.id`, `argi.agent.task.id`, `argi.agent.attempt.id`,
  `argi.agent.delegation.id`) and the previous MDC values are restored on close.

### Privacy defaults and exporter boundary

- No private chain-of-thought, full prompts, tool payloads, token counts, tokens or
  secrets are ever recorded as span attributes. Exception details go through
  `ObservationContentSanitizer` (sensitive-key redaction and length truncation) and
  only the redacted wrapper is handed to `observation.error(...)`.
- Enabling the tracer records *that* work happened and the identifiers needed to
  join spans — nothing else. Exporters, samplers and collectors remain application
  concerns; a `NOOP` registry keeps everything disabled with no allocation.

### Wiring

```java
AgentTracer tracer = new AgentTracer(observationRegistry); // null or NOOP = disabled

TaskToolsBuilder tools = TaskToolsBuilder.builder()
        .agentTracer(tracer);            // TaskTool + TaskOutputTool share one correlation store

A2aRemoteAgent remote = A2aRemoteAgent.builder()
        .agentTracer(tracer)             // send/receive spans + producer metadata
        .build();

RunStore observed = new ObservedRunStore(runStore, tracer); // submit/acquire/commit spans
```



ARGI Graph supports interrupting workflow execution at specific points, enabling human-in-the-loop scenarios.

## Interruption Support

### InterruptableAction Interface

```java
public interface InterruptableAction {
    // Called BEFORE node execution - can prevent execution
    Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config);

    // Called AFTER node execution - can inspect results and interrupt
    default Optional<InterruptionMetadata> interruptAfter(String nodeId, OverAllState state,
            Map<String, Object> actionResult, RunnableConfig config) {
        return Optional.empty();
    }
}
```

### Example Usage

```java
public class ReviewAction implements AsyncNodeActionWithConfig, InterruptableAction {

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of("result", "generated_content"));
    }

    @Override
    public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
        return Optional.empty(); // Don't interrupt before execution
    }

    @Override
    public Optional<InterruptionMetadata> interruptAfter(String nodeId, OverAllState state,
            Map<String, Object> actionResult, RunnableConfig config) {
        // Interrupt after execution for human review
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
            .addMetadata("reason", "needs_review")
            .addMetadata("content", actionResult.get("result"))
            .build());
    }
}
```
