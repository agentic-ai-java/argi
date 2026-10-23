# Persistence Ownership Boundary

Status: adopted for the Core/Extensions split. Reviewed 2026-10-24.

This document is the implementation decision for
[#190](https://github.com/agentic-ai-java/argi/issues/190). The split is based
on dependency ownership and operational scope, not on a mechanical package
rename.

## Contract and implementation map

| Capability | Contract owner | Implementation owner | Core status | Compatibility evidence |
| --- | --- | --- | --- | --- |
| Checkpoint read/write/release | `argi-loop-core` `BaseCheckpointSaver` | Loop: `MemorySaver`, `FileSystemSaver`; Extensions: JDBC, MongoDB, Redis | External drivers removed from Loop | `checkpoint-compatibility.md`; backend fixtures in Extensions |
| Versioned checkpoint CAS | `argi-loop-core` `VersionedCheckpointSaver` | Extensions Redis and future durable adapters | Contract remains in Loop | `VersionedCheckpointScope` and Redis CAS tests |
| Execution leases | `argi-loop-core` `LeasedCheckpointSaver` | Extensions Redis | Contract remains in Loop | lease scope and Redis fencing tests |
| General Store API | `argi-loop-core` `Store` / `VersionedStore` | Loop: `MemoryStore`, `FileSystemStore`; Extensions: durable stores | External stores removed from Loop | Store JSON fixtures and contract tests |
| Run/Task persistence | `argi-runtime-core` `RunStore` | Extensions durable adapters | Never owned by Loop backend classes | Runtime contract and Redis RunStore tests |
| State serialization | `argi-loop-core` serializer contracts | Loop serializer and backend adapters | Wire literals unchanged | `checkpoint-compatibility.md` |
| Database drivers and auto-configuration | No Core contract | Extensions persistence modules and starters | No provider driver in Loop runtime | POM dependency and module boundaries |

The contract modules own interfaces, records, compatibility rules and in-memory
reference implementations. Extensions owns all optional database clients,
backend schemas, transactions, leases, retry behavior and auto-configuration.
The same backend must not receive bug fixes in both repositories.

## Overlap analysis

Before this change, Loop contained JDBC checkpoint savers for H2, MySQL,
PostgreSQL and Oracle, plus MongoDB and Redis savers. It also contained JDBC,
MongoDB and Redis `Store` implementations. Extensions contained corresponding
JDBC, MongoDB and Redis implementations under `graph-persistence`, with newer
Redis lease, CAS, retention and RunStore behavior.

The overlap is real implementation duplication, not merely optional dependency
metadata:

- both JDBC trees implement checkpoint history, release and retention tables;
- both MongoDB trees implement thread metadata and serialized checkpoint
  documents;
- both Redis trees implement thread activation, checkpoint buckets and locks;
- both store trees implement the same namespace/key/value contract;
- Extensions additionally owns cross-process fencing and durable RunStore
  behavior that cannot be maintained correctly in a Loop-only module.

The intentional differences are retained as Extensions capabilities: Redis
leases, versioned CAS, run commits and backend-specific integration tests. They
are not copied back into Loop.

## Decision comparison

| Option | Benefit | Cost/risk | Decision |
| --- | --- | --- | --- |
| Keep every backend in Loop | No immediate consumer changes | Core ships drivers, duplicate fixes and backend lifecycle; independent Agent/Workflow consumers inherit storage surface | Rejected |
| Keep both trees with informal ownership | Smallest source diff | Future fixes can diverge; no enforceable dependency boundary | Rejected |
| Make Loop delegate to Extensions | One behavior implementation | Core would depend on optional integrations and create a cycle for Extensions contracts | Rejected |
| Keep contracts plus memory/file in Loop; move durable backends to Extensions | Small Loop artifact, one backend owner, provider isolation, durable features remain available | Durable consumers change artifact and imports; requires cross-repository release alignment | Adopted |

This is a deliberate breaking implementation change. It does not change
checkpoint fields, Redis keys, database table names, serializer markers,
namespace encoding or configuration semantics. It changes only where durable
backend classes and drivers are delivered.

## Migration

1. Keep the matching Core and Extensions versions together.
2. Replace Core backend imports with the Extensions classes:
   `io.github.agentic.ai.graph.persistence.jdbc.*`,
   `io.github.agentic.ai.graph.persistence.mongodb.MongoSaver`, and
   `io.github.agentic.ai.graph.persistence.redis.*`.
3. Add the relevant `argi-graph-persistence-*` artifact and its driver through
   the Extensions BOM.
4. Keep existing `RunnableConfig`, checkpoint fields, namespaces, table names,
   Redis keys and serializer configuration unchanged.
5. For a rollback, pin the previous Core/Extensions pair. No checkpoint or
   Store data conversion is required.

The Extensions persistence modules now compile against `argi-loop-core` at the
matching RC2 snapshot. Other Extensions modules that still consume the older
graph coordinate remain outside this focused migration and are tracked by
their own consumer tasks; they must not be silently changed as part of #190.

## Verification contract

Every durable backend adapter must run the common contract cases for:

- fresh write, latest read, history and release/reuse;
- retention and serializer round trip using the 2.1.0 fixtures;
- concurrent writers, revision conflicts and lease fencing where supported;
- restart persistence using the real backend;
- transaction or atomic-write rollback without deleting the original record;
- Core-only dependency-tree isolation and Core+Extensions BOM loading.

Core's Loop tests now cover the memory/file reference implementations and the
neutral serializer/checkpoint contracts. Extensions retains JDBC H2/MySQL/
PostgreSQL/Oracle, MongoDB and Redis backend tests, including real-container
tests where the environment provides the service. A local build without those
services must not be reported as a real-backend validation.

## Removal rule

Adding a database client or durable backend implementation to `argi-loop-core`
is a boundary violation. New persistence work belongs in an Extensions
`graph-persistence` module and must add or reuse a contract test from Core.
