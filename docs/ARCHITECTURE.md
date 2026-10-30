# Architecture

CARP-DSP has two parts: a domain model for sensing analytics workflows, and an
implementation that plans and runs them.

## Domain model

The domain model lives in `carp.analytics.core`, a module of CARP Core. CARP Core
already models studies, study protocols, deployments and the typed data streams
they collect; the analytics module adds the types the analysis lifecycle needs:

| Type | Role |
| --- | --- |
| `WorkflowDefinition`, `Workflow`, `Step` | What an analysis is |
| `EnvironmentDefinition` | Where a step runs (interface) |
| `TaskDefinition` | What a step runs (interface) |
| `DataReference` | What a step reads and writes (interface) |
| `ExecutionPlan`, `PlannedStep` | What planning produces |
| `ExecutionReport` | What a run records |

The types carry no infrastructure dependency. Because a workflow uses CARP's data
types, a port can declare what a table measures, and an input can be checked
against the protocol of the study that collects it.

## Lifecycle subsystems

The implementation is four subsystems, one per lifecycle phase, that reach the
outside world only through interfaces.

| Phase | Main components | Produces |
| --- | --- | --- |
| Authoring | `WorkflowYamlCodec`, `WorkflowDescriptor` | A workflow document |
| Planning | `UsesResolver`, `WorkflowLinter`, `ExecutionPlanner` | An `ExecutionPlan` |
| Execution | `EnvironmentHandler`, `StepRunner`, `ArtefactStore` | A run folder and report |
| Sharing | `PackageBuilder`, `RegistryPort` | A package and its exports |

## Modules and source sets

| Module | Contents |
| --- | --- |
| `carp.dsp.core` | Authoring, YAML codec, linting, planning, execution, packaging |
| `carp.dsp.steps` | The certified step library ([STEP_LIBRARY.md](STEP_LIBRARY.md)) |
| `carp.dsp.demo` | Demo workflows and the evaluation harness |

`carp.dsp.core` is Kotlin Multiplatform. The domain-facing code (descriptors,
linting, planning, workspace and environment abstractions) is in `commonMain` and
has no JVM dependency. File system access, subprocesses, YAML parsing, the
environment handlers, exports and the registry client are in `jvmMain`.

CARP-DSP depends on CARP Core one way: CARP Core knows nothing of CARP-DSP. Both,
with `health-workflow-interfaces`, are built as one Gradle composite build from
sibling checkouts (see the [README](../README.md#building-v100)).

## Design decisions

- **Dependencies point inward.** Domain types depend on nothing; lifecycle
  services depend on the types; environment managers, file systems and registries
  sit behind interfaces at the edge. A new capability is added by implementing an
  interface.
- **YAML is one encoding of the model, not its definition.** The codec returns a
  sealed result (`Success`, `MalformedYaml`, `SchemaError`, `PolicyViolation`), so
  callers handle every failure without exceptions. Unknown fields are ignored, so
  an older runtime can load a newer document.
- **Order and data flow are declared separately.** `dependsOn` orders steps; an
  input's `source` says where its data comes from. A step can wait for another
  without reading its outputs.
- **Plans are deterministic.** Step, port and environment identifiers are UUID v5
  values derived from the workflow's id and version and the element's key. The
  topological sort is Kahn's algorithm with ties broken by declaration order.
- **Isolation by process and folder, not by container.** Each step runs in its own
  process with its own folders inside a declared environment, so a workflow stays
  editable without building an image. Writes outside a step's folders are
  reported, not prevented.
- **Reuse is a pinned reference.** A library step is referenced with `uses:` and
  pinned by content hash in `steps.lock`, so a changed step is caught as drift.
- **Kotlin steps are command tasks.** A Kotlin step runs
  `java -cp {carp.taskRuntime} <entry point>` in its own JVM, so it passes the same
  gate as any other step.

## Extension points

| To add | Implement |
| --- | --- |
| An environment kind | An `EnvironmentDefinition`, a reference type in the plan model and an `EnvironmentHandler`, plus the dispatch lines in the importer, resolver and handler registry |
| A registry backend | `RegistryPort` |
| A protocol source | The data-type provider used for protocol-linked inputs ([PROTOCOL_COUPLING.md](PROTOCOL_COUPLING.md)) |
| A library step | A step folder in `carp.dsp.steps` ([STEP_LIBRARY_CONTRIBUTING.md](STEP_LIBRARY_CONTRIBUTING.md)) |
