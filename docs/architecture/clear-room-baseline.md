# Clear Room Baseline and Naming Decisions

Status: adopted for the first Loop migration slice. Reviewed 2026-10-24.

This document records the evidence and decisions required by [#181](https://github.com/agentic-ai-java/argi/issues/181).
It is a migration input, not a promise to rename every existing type.

## Source baselines

| Repository | Evidence | Decision |
| --- | --- | --- |
| Core | `alibaba/spring-ai-alibaba` tag `v1.0.0-M2`, commit `4db66e296356f981ecca9885c9b07d3a2f153fc6`, dated 2024-09-22. The same commit is present in the imported ARGI history. | Use this as the last directly verifiable upstream Core baseline. Later ARGI commits are treated as ARGI-owned changes unless a commit is explicitly linked to an upstream object. |
| Extensions | `spring-ai-alibaba/spring-ai-extensions` tags are available, including `v1.1.0.0-M3` (`270b96f200da`) and `v2.0.0-M1.1` (`9f4cfe33697f`). No matching upstream object was found for the ARGI Extensions baseline `495414b76a37aca4d7c1022d973a8e5b9a7e92e6`. | Keep the Extensions import base marked unknown. Do not substitute upstream `main` or a tag for the missing ancestry evidence. Reconstruct ownership from the ARGI commit history when an Extensions capability is changed. |
| ARGI Core | Current migration baseline is `53635bd1f` (`main`) after PR #257. | New work must build on the Loop artifact and preserve the explicit data compatibility rules below. |

The source repositories, tag names, commit IDs, and dates above were checked from
the public GitHub API on 2026-10-24. An unknown ancestry is an evidence gap, not
permission to infer a migration point.

## Responsibility map

| Legacy path or concept | Current owner | Target or retention decision |
| --- | --- | --- |
| `argi-graph-core` / `argi-graph-core` Maven artifact | `argi-loop-core` / `argi-loop-core` | Adopted breaking artifact replacement in #198. No compatibility artifact is published. |
| `io.github.agentic.ai.graph.StateGraph`, `CompiledGraph`, node, edge, and executor types | `argi-loop-core` | Retain FQCNs. These names describe graph topology and execution accurately; package renaming is deferred until #182 and #183 establish a safe migration. |
| Graph state, checkpoint savers, graph serializers, and graph recovery | `argi-loop-core` | Retain graph semantics and historical wire literals. Persistence adapters remain optional integrations. |
| Identity, deadline, budget, delegation, Run/Task data, and definition admission | `argi-runtime-api` and `argi-runtime-core` | Keep extracted neutral contracts outside Loop. Loop consumes them but does not own their definitions. |
| ReAct execution | `argi-agent-core` | Independent execution loop; no Loop or Agent Framework dependency. |
| Declarative Workflow execution | `argi-workflow-core` | Independent execution engine; no Loop or Agent Framework dependency. |
| Existing graph-backed Agent Framework flow agents | `argi-agent-framework` plus optional Loop dependency | Retain until a replacement flow implementation is proven. The dependency is an integration choice, not a requirement of the independent Agent or Workflow engines. |
| `MemorySaver` / graph checkpoint memory | Loop | Graph checkpoint semantics and namespace rules remain Loop-owned. |
| `MemoryRunStore` / Run data memory | Runtime Core | Run reservation, fencing, and revision semantics remain outside Loop. |
| `Store` and database-backed store implementations | Core plus Extensions under the #190 ownership decision | Do not duplicate a second store contract during naming work. |
| Document, rerank, provider SDK, and vendor model types | Extensions or API adapters | Keep vendor names where they identify an external protocol or SDK. Do not move provider dependencies into Loop. |

## Terminology

- **Graph** means the node/edge topology and the graph execution model exposed by
  Loop. `StateGraph` and `CompiledGraph` remain precise public type names.
- **Loop** means the independently consumable product boundary that owns
  resumable, stateful graph execution. It is not a synonym for `LoopAgent`, a
  Workflow loop step, or an internal ReAct turn loop.
- **Workflow** means a declarative definition and execution model. A Workflow may
  contain a loop step without constructing a `StateGraph`.
- **Run** is a recoverable execution instance. **Task** is a child unit of a Run.
  Neither term implies graph execution.
- **Registry** stores versioned definitions or runtime components. **Registrar**
  performs admission or registration; **Registration** is the immutable result.
- **State** is execution data owned by an engine. **Checkpoint** is a persisted
  resumable state boundary. They are not interchangeable names.
- **Memory** is qualified by its owner (`MemorySaver`, `MemoryRunStore`, or
  `MemoryStore`) because the unqualified term has three different semantics.

## Naming rules

1. Use the `Argi` prefix for ARGI-specific public facades, configuration objects,
   and integrations where it prevents collision with a provider or framework.
2. Do not prefix neutral industry concepts such as `StateGraph`, `Checkpoint`,
   `RunStore`, `Workflow`, or `ToolCall` merely to distinguish ARGI.
3. Preserve SDK and vendor names in adapters and historical wire identifiers.
   They are compatibility data, not ARGI branding.
4. New Maven artifacts use the `argi-*` namespace. The Loop product artifact is
   `io.github.agentic-ai-java:argi-loop-core`.
5. Configuration keys remain under `argi.*` unless a separate issue proves that
   a key is owned by another engine. Existing `argi.graph.*` observation names
   remain stable data and metric identifiers.

## Breaking-change register

| Change | Problem evidence | Alternatives considered | Decision and migration |
| --- | --- | --- | --- |
| `argi-graph-core` artifact to `argi-loop-core` | The old artifact name coupled graph topology, persistence, and the new Loop product boundary, and prevented a clear independent consumer contract. | Keep the artifact; publish a second alias; replace the artifact. A second alias leaves two dependency identities and does not clarify ownership. | Replace directly in #198. Update BOM, starters, examples, benchmarks, and consumers. Roll back by pinning the previous ARGI release; no persisted data conversion is required. |
| Graph Java package and type names | Existing source references and serialized class names use `io.github.agentic.ai.graph.*`. | Rename all packages; add wrappers; retain names. A mechanical rename adds source, binary, and wire migration cost without a demonstrated behavior benefit. | Retain for now. Revisit only with a measured compatibility plan under #182/#183. |
| `graphId`, `graphVersion`, `DefinitionKind.GRAPH`, and `argi.graph.*` wire names | Run definitions, observability, and persisted data use these literals. | Rename immediately; add aliases; retain literals. | Retain literals. Product naming and data naming are separate decisions. |

## Consumer migration

1. Replace the Maven coordinate `io.github.agentic-ai-java:argi-graph-core` with
   `io.github.agentic-ai-java:argi-loop-core` and import the matching BOM.
2. Replace module selectors and local paths from `argi-graph-core` to
   `argi-loop-core`.
3. Do not change `io.github.agentic.ai.graph.*` imports, checkpoint keys, graph
   IDs, graph versions, or graph observation names in this slice.
4. Rebuild graph-backed framework integrations after the coordinate change.
5. For durable data, follow the compatibility matrix in
   `checkpoint-compatibility.md`; an artifact rename alone does not require a
   checkpoint rewrite.

## Review conclusion

Adopt the artifact boundary, neutral runtime extraction, and independent Agent
and Workflow engines already merged. Retain mature graph terminology, Java
packages, and persistence literals. Defer package and configuration renames until
there is a concrete ownership problem, a migration plan, and fixture evidence.
