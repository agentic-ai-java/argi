# ARGI Runtime API

`io.github.agentic-ai-java:argi-runtime-api` contains the JDK-only identity, definition and
execution control contracts shared by execution engines. It requires Java 17
and has no production dependencies, including Graph, Agent, Workflow, Reactor,
Spring, Jackson, model SDKs or storage drivers.

This is the shared contract foundation for [#200](https://github.com/agentic-ai-java/argi/issues/200)
and the Loop boundary in [#198](https://github.com/agentic-ai-java/argi/issues/198).
Run/Task data, codecs and reference storage are in [Runtime Core](../argi-runtime-core/README.md).
Neither module is an execution engine or completes the Loop migration.

## Contracts available now

The following classes have one implementation, owned by this artifact:

| Class | Responsibility |
| --- | --- |
| `TenantReference` | Application-owned tenant identity, without credentials |
| `PrincipalReference` | Application-owned principal identity, without credentials |
| `ExecutionBudget` | Immutable named limits; usage accounting is owned by an executor |
| `DelegationScope` | Permissions that a parent may narrow for child execution |
| `TrustedExecutionContext` | Authenticated identity, run/correlation IDs, deadline, budget and delegation scope |
| `TrustedExecutionContextReference` | Versioned persistence data that must be revalidated before use |
| `TrustedExecutionContextResolver` | Application boundary for re-establishing trusted identity on recovery |
| Definition references, resolver SPI and worker/recovery declarations | Exact version selection and explicit capability checks |
| `ExecutionGuard` / `ExecutionControl` | Cooperative cancellation, deadline checks and ownership loss without lease credentials |

`argi-graph-core` depends on this artifact. The contracts neither invoke Graph
nor load it through reflection. Moving them is a dependency extraction, not an
adapter that delegates to the old engine.

## Ownership decisions and remaining work

| Current capability | Target owner | Decision in this slice |
| --- | --- | --- |
| Identity, immutable limits and delegation | Runtime API | Extract the seven contracts above |
| `TrustedExecutionContextCodec` | Optional runtime serialization support | Extract into Runtime Core; keep Jackson out of the API |
| Cancellation, deadline enforcement and execution ownership | Shared runtime contracts plus executor enforcement | ExecutionControl supplies local stop checks; executors own timers, usage accounting and side-effect cancellation |
| Run/Task and versioned definition references | Runtime API and Runtime Core | References/resolver in API; Run/Task and strict v1/v2 codecs in Core |
| RunStore and in-memory reference implementation | Runtime services | Extract into Runtime Core and preserve the atomicity/fencing contract |
| Graph topology, compilation, routing, parallel merge and subgraphs | Loop core | Retain in Graph until the dedicated engine migration |
| Graph state, graph checkpoints and graph recovery | Loop core and persistence SPI | Retain graph semantics; align historical data handling with #182 |
| General state storage and database implementations | Shared storage contracts and Extensions | Compare implementations and assign a single maintenance owner under #190 |
| Model streams, messages, tools and skills | Agent/model capabilities and optional Loop integrations | Move out of the basic engine in a later slice; retain their current behavior now |
| Graph observations versus agent/run tracing | Loop integration versus shared runtime integration | Separate by semantics; do not move all observation classes as one package |
| Scheduling and Studio | Optional runtime services and management clients | Keep current entry points until their replacement contracts exist |

Workflow will execute independently of Loop. ReAct will also have its own
execution loop. Neither engine is made independent by this extraction alone.

## Package and compatibility decision

The extracted contracts retain their existing
`io.github.agentic.ai.graph.execution` fully qualified names. ExecutionGuard also retains its original checkpoint.lease name;
new ExecutionControl types use io.github.agentic.ai.runtime.execution.
The package name is transitional; it does not represent an artifact dependency.

Alternatives considered:

- Keeping the classes inside Graph preserves compatibility but prevents a
  runtime-only consumer from using identity and budget contracts.
- Moving and renaming them together removes the historical namespace but breaks
  source/binary references and class-name-based payloads before #182 establishes
  migration rules. Introducing wrappers would also create competing identity types.
- Moving the existing implementations while preserving their names removes the
  dependency immediately without changing identity equality, public signatures
  or serialized type names. This is the adopted first step.

The current build uses the Java classpath, with no JPMS module descriptors. The
Runtime Core codecs and these contracts temporarily share a package across artifacts;
this slice does not introduce a module-path compatibility guarantee. Final
namespace migration must resolve that split together with codec ownership and
historical payload handling, before the Loop migration is considered complete.

## Consumption and upgrade

```xml
<dependency>
    <groupId>io.github.agentic-ai-java</groupId>
    <artifactId>argi-runtime-api</artifactId>
</dependency>
```

Import the matching `argi-bom` to manage the version. The first target is
`2.1.0-RC2-SNAPSHOT`; publishing remains a separate operation.

- Existing Maven consumers of Graph receive this artifact transitively. Existing
  imports and configuration keys remain unchanged. See Runtime Core for Run/Task v2
  adoption and record-layout compatibility.
- Runtime-only consumers depend directly on this artifact.
- Manually assembled classpaths must add the matching runtime API jar when
  upgrading Graph. Do not combine an old Graph jar containing these classes
  with the extracted runtime API jar.
- No state or checkpoint conversion is required for the identity extraction. Reference
  JSON remains schema version 1; decoded references are still not authentication.
- Roll back the matching Graph/runtime API/runtime Core/BOM set together. Identity
  data needs no conversion; if v2 runs have been admitted, follow the Runtime Core
  reader-upgrade and rollback rules rather than exposing them to old workers.

## Verification

```sh
./mvnw -B -pl :argi-runtime-api -am test
tools/scripts/verify-api-contract.sh
```

The API consumer gate compiles and executes a separate runtime-only consumer
with only the runtime API jar and JDK on its execution classpath. It checks
delegation, immutable constraints, reference resolution and the absence of
Graph, Agent, model, Spring and Reactor classes. JDK `jdeps` checks the runtime
jar's actual bytecode dependency set. Existing Graph and Agent tests continue
to validate propagation, authorization and versioned storage behavior.
