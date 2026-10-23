# ARGI Runtime Core

`io.github.agentic-ai-java:argi-runtime-core` provides engine-neutral Run/Task data,
versioned codecs, definition admission and an in-memory reference RunStore. It has
only Runtime API and Jackson production dependencies. It does not execute agents,
build graphs, schedule workers, load provider SDKs or install database drivers.

## Ownership

| Capability | Owner |
| --- | --- |
| Identity, budget, delegation and definition references/resolution SPI | Runtime API (JDK-only) |
| ExecutionGuard and cooperative cancellation/deadline/ownership control | Runtime API (JDK-only) |
| Run/Task, namespace, fencing lease, revisions, journal/outbox and store SPI | Runtime Core |
| JSON validation, canonical payload hashing and versioned codecs | Runtime Core |
| Definition registry and atomic in-memory reference store | Runtime Core |
| ObservedRunStore and Graph tracing | Optional Graph integration |
| Topology, graph state, checkpoints and lease renewal scheduler | Graph/Loop engine |
| Tool cancellation tokens and model/result adapters | Agent integration (#195) |
| Database adapters and durable workers | Extensions and #124/#190 |

## Design Decision

RunPayload currently validates and canonicalizes JSON in its constructor. Keeping
Run/Task in Graph preserves coupling; adding Jackson to Runtime API removes its
JDK-only guarantee; replacing the mature JSON implementation would change hashing
and input rejection. The adopted split preserves those semantics in this bounded
data module. It is not a monolithic runtime or a facade over Graph.

RunSpec now carries a DefinitionReference (kind, id, version), rather than assuming
every executor is a graph. AGENT and WORKFLOW are independent execution kinds;
GRAPH continues to mean graph topology, including the future Loop engine. The
product rename does not change the stable graph identifier.

## Execution Boundaries

ExecutionControl checks the persisted millisecond deadline, explicit cancellation
and an optional ownership guard. Executors call assertActive before and after
side effects and register cancellation for their own model/tool requests. Local
callbacks run at most once; runtime callback failures do not suppress later
notifications. Registration handles detach listeners; closing detaches ownership.
No timer, background executor or implicit retry is installed. Blocking operations
still require executor-owned timeouts/cancellation. A local guard is not proof of
storage ownership: RunStore commits must atomically validate the fencing token.

Budget limits remain immutable constraints, not usage accounting. Each executor
owns usage reservation and accounting. Identity references remain unauthenticated
data; recovery must revalidate them with TrustedExecutionContextResolver.

RunState records lifecycle, RunPayload carries application-versioned data, and
RunEntry carries append-only journal/outbox events. A result can be committed as
an application-typed completion event in the same transaction as terminal state.
There is no shared model-specific token stream or graph-state result requirement.

## Compatibility Matrix

Target version: 2.1.0-RC2-SNAPSHOT. Publishing is a separate operation.

| Surface | Policy |
| --- | --- |
| Existing FQCNs | Retained; one implementation per class, now in runtime artifacts |
| Legacy RunSpec constructor/from and graph accessors | Retained for GRAPH; other kinds fail explicitly |
| RunSpec v1 JSON | Read and write original graphId/graphVersion fields, unchanged hash |
| RunDefinitionRecord v1 JSON | Read and write original graph field and GRAPH enum value |
| RunSpec/definition v2 JSON | Explicit definition field; old workers must reject it |
| TaskSpec, identity, journal/outbox schemas | Remain version 1 |
| DefinitionKind wireName | Existing values retained; agent/workflow added |
| Checkpoint/ObjectStream formats and configuration keys | Unchanged; no package rename |
| Raw Jackson/reflection over record components | Changed: use the documented codecs, not record layout as a protocol |
| RunStoreContractTest test fixture | Now published by runtime-core test-jar, same FQCN |

Codecs reject unknown fields, duplicate keys, missing or invalid schema versions,
integer overflow and mixed legacy/neutral envelopes. Definition resolution matches
the exact kind/id/version; deduplication includes the kind, not only id/version.
Graph v1 and v2 submissions with equivalent identity/input/definition deduplicate
to the original stored record without upgrading it implicitly.

Existing graph-specific constructors keep writing v1. The DefinitionReference
RunSpec factory writes v2. Upgrade all readers before enabling v2 admission; retain
pinned definitions until no live run references them. No v1 conversion is needed.
There is no lossless downgrade of agent/workflow records to graph records. Drain
or retain v2-aware workers before rolling back. Roll back Core, Runtime API/Core
and BOM together; never combine old Graph jars containing the moved classes with
new runtime jars. Maven consumers receive runtime artifacts transitively; manually
assembled classpaths must add both.

The classpath build still uses transitional graph.execution packages. It does not
promise JPMS support for split packages. Final namespace migration belongs to
the Loop/data migration work, rather than silently breaking stored Java names.

## Verification and Remaining Engine Work

    ./mvnw -B -pl :argi-runtime-core -am test
    tools/scripts/verify-api-contract.sh

Fixtures under src/test/resources/execution were generated with the pre-extraction
Graph jar from the #218 baseline (8b16e46ce), before rebuilding the new modules.
Tests require byte-identical v1 round trips, not just new-writer/new-reader agreement.
The moved RunStore contract covers atomicity, lease expiry/fencing, tenant isolation,
deduplication, deadline narrowing and state transitions. Separate JVM tests verify
pinned definition recovery; they do not establish database durability.

The consumer gate runs agent-, workflow- and graph-kind contract consumers with
only Runtime Core, Runtime API and Jackson. They resolve an application-owned
action and commit its result without loading an engine. They are not replacement
ReAct/Workflow implementations. #195/#196/#197 still own independent execution;
#198 owns Loop artifact/package migration and optional Agent bridges. This change
removes their shared-runtime dependency obstacle, not those remaining tasks.
