# Protocol Coupling (F5) - Requirements and Design

**Status:** In progress. Branch `feature/protocol-coupling` (carp-dsp) +
`feature/core-analytics` (carp.core-kotlin).
All three slices implemented: source variants + importer + linter warning,
plan-time protocol check, and the mixed-workflow fixture + demo
(`protocol-coupling-eval`). Slices 1-2 verified by `:carp.dsp.core:jvmTest`;
slice 3 fixtures verified structurally, pending a local run.
**Requirement:** F5 - Protocol Linkage, upgraded from metadata-only to plan-time validation.

## Goal

The planner checks, before any step runs, that the data a workflow expects from
outside the pipeline is actually collected by the study protocol it is linked to.
A workflow cannot be planned against a protocol that does not collect the data it
needs.

This closes the loop between data collection (the protocol) and analysis (the
workflow), using the same plan-time gate that already catches structural faults.

## The key constraint: not all data comes from a protocol

CARP-DSP also runs on open data (e.g. the mobgap LabExample set, the Fitbit
Zenodo set) and on protocol data mixed with open data. So protocol validation
cannot be a whole-workflow gate. Binding is **per data input**, not per workflow.

## Model

Each boundary input - an input a step reads from outside the pipeline, rather
than from an upstream step - declares where its data comes from. This rides on
the existing input `source` union, which today only carries `step-output`. Two
new variants are added:

| source `type`   | meaning                                   | validated against a protocol? |
| --------------- | ----------------------------------------- | ----------------------------- |
| `step-output`   | produced by an upstream step (existing)   | no (typed-port check already) |
| `protocol`      | collected by a named study protocol       | yes                           |
| `external`      | open data, an upload, or a prior export   | no                            |

Because binding is per input, a workflow can reference **several protocols** at
once (one input from study A, another from study B) with no extra machinery: each
`protocol` source names its own protocol. The common case (one protocol) stays
simple; multiple protocols fall out for free.

External data is a designed affordance, not a coverage gap. A workflow may mix
protocol-sourced and external inputs freely; only the protocol-sourced ones are
checked.

### Source shapes

```yaml
# existing
source: { type: step-output, stepId: aggregate, outputId: dmos-csv }

# new: collected by a protocol
source:
  type: protocol
  protocol: <protocol ref>       # links to the protocol, not a deployment
  dataType: <CARP DataType>      # e.g. dk.cachet.carp.heartrate

# new: open / external data (all fields optional)
source:
  type: external
  uri: "https://zenodo.org/record/53894"   # optional
  citation: "Furberg et al. 2018"          # optional
```

Note the `dataType` on a `protocol` source is a CARP **DataType** (the domain
measurement, e.g. heart rate), which is distinct from the input's file-format
`descriptor.type` (e.g. `csv`). Protocol matching is on the domain DataType, not
the file format - the same domain-vs-structural typing distinction the framework
rests on.

## Implementation (slices 1-2)

- `ProtocolInputSource` / `ExternalInputSource` on the `InputSource` union, with
  `ProtocolRefDescriptor` (`id`, optional `version`, optional `name`); registered in
  `descriptorSerializersModule`.
- `PortImporter` carries provenance into the domain model as `DataLocation.metadata`
  keys (`source`, `protocolId`, `protocolVersion`, `dataType`, `uri`, `citation`),
  so no domain type change was needed.
- `WorkflowLinter` check 9 emits `EXTERNAL_DATA_UNATTRIBUTED` (WARNING).
- `ProtocolCouplingValidator` performs the plan-time check; the planner depends on
  the narrow `ProtocolDataTypeProvider` interface, so the protocols subsystem is
  not a hard planner dependency.
- `StudyProtocolSnapshotDataTypeProvider` implements that interface over supplied
  `StudyProtocolSnapshot`s (selects by id and version; latest when unspecified).
- `DefaultExecutionPlanner( protocolDataTypeProvider = ... )` - optional constructor
  argument, so every existing `DefaultExecutionPlanner()` call site is unchanged.
- Build: `carp-core-protocols` added to the composite-build substitution map and to
  `carp.dsp.core` dependencies.

## Validation behaviour

- **`protocol` input, type not collected by the named protocol** -> plan-time
  ERROR `PROTOCOL_DATA_NOT_COLLECTED`, naming the input, its DataType, and the
  protocol. No step executes. (Same behaviour as the structural fault checks.)
- **`protocol` input, type present** -> passes.
- **`external` input with no `uri`/`citation`** -> plan-time WARNING (unattributed
  external data), never an error. The plan still runs. Missing provenance is a
  documentation gap, not a correctness fault.
- **Boundary input with no provenance given** -> treated as empty `external`
  (same warning). Non-breaking, so existing workflows keep planning.
- **Workflow with no `protocol` inputs** (all external, or pure open data) ->
  zero protocol errors. The check simply does not apply.

## Requirements

- **F5.1** A boundary input declares a typed data requirement and a provenance
  via its `source` (`protocol` | `external`), reusing the existing `source` union.
- **F5.2** A workflow may reference more than one protocol; each `protocol`
  source names the protocol its data comes from.
- **F5.3** The planner can read a referenced protocol's set of collected CARP
  DataTypes (a read into the CARP protocol model; no new domain modelling).
- **F5.4** At plan time, every `protocol`-bound input's DataType must be present
  in its protocol's collected set, else `PROTOCOL_DATA_NOT_COLLECTED`. `external`
  and `step-output` inputs are not protocol-checked.
- **F5.5** Missing or empty external provenance yields a warning, not an error;
  workflows with no protocol binding plan with zero protocol issues.

## Scope

**In scope:** presence/type validation of protocol-sourced inputs against a
protocol *definition* (an authored artifact - no live study or deployment needed).

**Out of scope (stays future work):**
- Adequacy checks - sampling rate, units, duration, device semantics.
- Runtime binding to a live deployment and event-triggered execution
  (the continuous-operation piece, deferred separately).

## Running the acceptance case

```
./gradlew :carp.dsp.demo:run --args "run protocol-coupling-eval"
```

Fixtures (`carp.dsp.demo/src/jvmMain/resources/`):

- `workflows/protocol-coupling-mixed.yaml` - two-step workflow whose boundary
  step takes one `protocol` input (heart rate) and one `external` input (open
  Fitbit data, Zenodo 53894), with a normal `step-output` input downstream.
- `protocols/hr-study-protocol.json` - snapshot v1, collects heart rate and step
  count -> the workflow plans clean.
- `protocols/steps-only-protocol.json` - same protocol id, snapshot v2, collects
  step count only -> `PROTOCOL_DATA_NOT_COLLECTED`, no step planned.

The eval also plans with no protocol supplied, showing the
`PROTOCOL_NOT_VALIDATED` warning. Results go to
`eval_results/protocol-coupling.{txt,csv}`.

## Acceptance criteria

1. A workflow with a `protocol` input for heart rate, linked to a protocol that
   collects only step counts, is rejected at plan time with
   `PROTOCOL_DATA_NOT_COLLECTED`, and the resulting plan is not runnable
   (`ExecutionPlan.isRunnable()` is false because the plan carries an ERROR), so
   no step executes.

   Note the plan object still *contains* its planned steps - planning completes
   and reports, rather than aborting - so the meaningful signal is
   `isRunnable()`, not the step count. The executor enforces this (see below).
2. The same input against a protocol that collects heart rate plans clean.
3. A **mixed** workflow - one `protocol` input (checked) plus one `external`
   input (skipped) - plans according to the protocol input alone. This is the
   demonstration that separates CARP-DSP from the systems in the comparison
   table: it validates protocol-sourced data while still supporting open data in
   the same pipeline.
4. An `external` input with no `uri`/`citation` produces a warning, not a failure.

## Follow-on

[PROTOCOL_LINEAGE.md](PROTOCOL_LINEAGE.md) proposes lifting the protocol
identifiers these declarations carry into package metadata and the lineage
graph, so the collection-analysis link is resolvable from the registry
without opening the workflow.

## The "no step executes" guarantee is enforced

`ExecutionPlan` exposes `hasErrors()` and `isRunnable()` ("Runnable means: the
planner produced no ERROR issues"). Previously nothing in carp-dsp called them,
so "an invalid plan does not run" held only by *caller convention*.

`DefaultPlanExecutor.execute()` now gates on it: a plan where `!isRunnable()` is
refused before any workspace is created and before any step runs. The refusal is
reported rather than thrown, so the caller still gets a traceable
`ExecutionReport` (NF5) with status `FAILED`, no step results, and a single
`POLICY_VIOLATION` issue naming the plan errors. Warnings never block.

This makes the guarantee mechanical for every planning error - structural faults
(Section: error detection) as well as unmet protocol bindings.

## CARP core integration (firmed up)

Verified against `carp.core-kotlin` (`develop`). carp-dsp currently depends on
`carp.common`, `carp.data`, and `carp.analytics`; protocol coupling adds a
dependency on **`carp.protocols.core`** (for `StudyProtocol` /
`StudyProtocolSnapshot` / `TaskConfiguration`). `DataType` is already available
through `carp.common`, so no new dependency there.

### 1. Protocol reference form

A `StudyProtocol` is identified by `id: UUID` (its `name` is unique only per
`ownerId`, so it is not a safe key), and protocols are **versioned** via
`ProtocolService` (`add`, `addVersion`, `getBy`, `getVersionHistoryFor`). The
reference therefore is:

```yaml
protocol:
  id: "<protocol UUID>"     # StudyProtocol.id
  version: <int>            # optional; defaults to latest
  name: "<human label>"     # optional, for readability only - not the key
```

At plan time the planner resolves this to a `StudyProtocolSnapshot` - either from
a `ProtocolService.getBy(id, version)` call, or from a snapshot supplied directly
(e.g. a JSON snapshot loaded for the demo). The **supplied-snapshot path is what
keeps this testable with no live study**: a protocol is an authored, serializable
artifact independent of any deployment.

### 2. Collected-DataType set

There is no single `getExpectedDataStreams()` on `StudyProtocol`; the collected
types are derived from the protocol's tasks and measures. A `Measure` is a sealed
class, and only `Measure.DataStream` carries a type (`Measure.TriggerData` does
not). So the set the protocol collects is:

```kotlin
val collected: Set<DataType> =
    protocol.tasks
        .flatMap { it.measures }
        .filterIsInstance<Measure.DataStream>()
        .map { it.type }
        .toSet()
```

This yields exactly what F5.4 checks against - a set of `DataType`s, device-
agnostic, which matches decision (2): we validate that the type is collected
*somewhere* in the protocol, not on a specific device. A `protocol`-bound input
declares its expected type as a CARP `DataType` name (e.g.
`dk.cachet.carp.heartrate`), and the check is simple set membership in
`collected`.

### Consequences for the requirements

- F5.3 is satisfied by the tasks/measures derivation above - no deployments
  subsystem needed, keeping the dependency footprint to `carp.protocols.core`.
- The reference is `id` (+ optional `version`); resolution goes through
  `ProtocolService` in production or a supplied `StudyProtocolSnapshot` for tests
  and the demo.
