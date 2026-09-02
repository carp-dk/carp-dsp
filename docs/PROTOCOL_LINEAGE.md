# Protocol Lineage in Package Metadata

**Status:** Implemented on `feature/protocol-lineage` (both repos); pending a
local build.
**Follows:** [PROTOCOL_COUPLING.md](PROTOCOL_COUPLING.md) (F5).
**Repos touched:** `health-workflow-interfaces` (model), `carp-dsp` (builder + lineage).

## Goal

Make the study protocols an analysis depends on visible in the artifact it
publishes, so the link between data collection and analysis is resolvable from
the registry without opening the workflow.

Protocol coupling already records this: a protocol-bound input names the
protocol it expects and the CARP data type it needs from it. Those identifiers
stay inside the workflow definition today. This proposal lifts them into the
package metadata and the lineage graph.

## Why it is worth doing

The paper claims the framework makes the collection-analysis relationship
explicit and citable. That is true inside a workflow, but a registry consumer
currently has to parse the native workflow content to discover it. Surfacing the
identifiers turns a "read the file" relationship into a queryable one: *which
analyses consume data from protocol X?* is the question a study team actually
asks, and it cannot be answered from the registry today.

## Current state

`PackageBuilder.build()` populates `PackageMetadata` with name, granularity,
description, tags, and sensitivity class only. `PackageMetadata` has no field for
protocol references, so there is nowhere to put them.

Note also that `PackageMetadata.inputs` / `.outputs` (`List<PortSummary>`) and
`.methods` exist but are never populated - a related gap, out of scope here.

## Design

### 1. Model (`health-workflow-interfaces`)

Add a reference type and one optional field. Both are additive with defaults, so
existing packages and stored records deserialise unchanged.

```kotlin
@Serializable
data class ProtocolReference(
    /** Study protocol id (UUID string). */
    val id: String,
    /** Protocol version; null means the reference was not pinned. */
    val version: Int? = null,
    /** Human-readable label for display; never a key. */
    val name: String? = null,
    /** Data types this workflow expects the protocol to collect. */
    val dataTypes: List<String> = emptyList(),
)

data class PackageMetadata(
    // ... existing fields unchanged ...
    val protocols: List<ProtocolReference> = emptyList(),
)
```

`dataTypes` is included deliberately: "this analysis consumes heart rate from
protocol X" is more useful to a consumer than the protocol id alone, and it is
free - the declarations already carry it.

### 2. Derivation (`carp-dsp`, `PackageBuilder`)

Collect protocol-bound inputs from the descriptor, group by protocol, and merge
their data types:

```kotlin
internal fun WorkflowDescriptor.protocolReferences(): List<ProtocolReference> =
    steps
        .flatMap { it.inputs }
        .mapNotNull { it.source as? ProtocolInputSource }
        .groupBy { it.protocol.id to it.protocol.version }
        .map { (key, sources) ->
            ProtocolReference(
                id = key.first,
                version = key.second,
                name = sources.firstNotNullOfOrNull { it.protocol.name },
                dataTypes = sources.map { it.dataType }.distinct().sorted(),
            )
        }
        .sortedWith( compareBy( { it.id }, { it.version } ) )
```

Deterministic ordering (sorted ids, sorted data types) matters because
`contentHash` and package equality are compared in tests and by the registry.

Wire into `PackageBuilder.build()`:

```kotlin
metadata = PackageMetadata(
    // ...
    protocols = descriptor.protocolReferences(),
)
```

A workflow with no protocol-bound inputs yields an empty list, so packages for
open-data-only workflows are byte-identical to today.

### 3. Lineage contract (`health-workflow-interfaces`, `LineageConformance`)

The lineage contract allow-lists node types and edge relations, so extending the
graph is not purely a carp-dsp change: `protocol` must be added to
`allowedNodeTypes` and `CONSUMES_FROM` to `allowedRelations`, or conforming
graphs containing them are rejected. This is deliberate - the allow-list is what
keeps lineage comparable across implementers - so the contract and the producer
have to move together.

### 4. Lineage (`carp-dsp`, `LineageGraphBuilder`)

`LineageGraphBuilder` already emits `environment`, `package` and `step` nodes
with typed edges. Add a fourth node type and an edge from the workflow's steps:

- node: `type = "protocol"`, `id` = protocol id, `version` = protocol version
  (or empty when unpinned), `label` = protocol name or id
- edge: step -> protocol, labelled to indicate consumption (e.g.
  `CONSUMES_FROM`), one per protocol-bound input

This makes the collection-analysis link a first-class edge in the same graph
that already carries environment and package provenance, rather than a separate
lookup.

## Scope

**In scope:** carrying protocol identifiers and their expected data types into
package metadata and the lineage graph.

**Out of scope:**
- populating `PackageMetadata.inputs`/`outputs`/`methods` (pre-existing gap)
- resolving protocol *names* from a protocol service at packaging time - the
  label comes from the workflow declaration, and may be absent
- server-side indexing or query endpoints in the registry; this proposal only
  ensures the data is present and structured

## Acceptance criteria

1. Packaging the mixed workflow (`protocol-coupling-mixed.yaml`) yields one
   `ProtocolReference` with the declared id and `dataTypes = ["dk.cachet.carp.heartrate"]`;
   the external input contributes nothing.
2. A workflow with two inputs bound to the same protocol yields one reference
   whose `dataTypes` contains both, sorted.
3. A workflow with inputs bound to two different protocols yields two
   references, ordered deterministically.
4. A workflow with no protocol-bound inputs yields an empty list, and its
   package is unchanged from the current output.
5. `LineageGraphBuilder` emits a protocol node per referenced protocol and a
   consumption edge per protocol-bound input.
6. Packages produced before this change still deserialise (default empty list).

## Effect on the paper

Section 7 currently says surfacing these identifiers in the registration record
"is a small addition still to be made". Once this lands, that sentence becomes
the stronger claim the section originally made: the lineage between a collection
protocol and an analysis workflow is resolvable through the registry without
inspecting either repository. Section 6's registration paragraph can then state
it as fact rather than availability.
