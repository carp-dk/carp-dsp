# CARP-DSP documentation

CARP-DSP (CARP Data Science Pipeline) is a workflow framework for mobile and
wearable sensing analytics, built on CARP Core. An analysis is a graph of
versioned steps with typed ports; the planner checks a workflow before it runs,
including whether a study protocol collects the data an input needs; and every
run leaves a folder that records what produced each output and can be run again.

This page describes the first major release, v1.0.0.

## The lifecycle

| Phase | What CARP-DSP does | Read |
| --- | --- | --- |
| Authoring | Workflows in a YAML language; a certified step library referenced with `uses:` | [Workflow reference](WORKFLOW_REFERENCE.md), [Using steps](STEP_LIBRARY_USAGE.md) |
| Planning | Structural, type and protocol checks before any step runs; deterministic plans | [Planning and running](RUNNING.md#planning), [Protocol coupling](PROTOCOL_COUPLING.md) |
| Execution | Declared, recorded environments; one process and folder set per step; step caching | [Planning and running](RUNNING.md#execution), [Step cache](STEP_CACHE.md) |
| Sharing | Run bundles with RO-Crate; packages; CWL and Snakemake export | [Sharing](SHARING.md) |

## Where to start

1. [README](../README.md): requirements and building from source.
2. [Quick start](../QUICKSTART.md): run a demo and read a workflow.
3. [Workflow reference](WORKFLOW_REFERENCE.md): write your own.
4. [Architecture](ARCHITECTURE.md): how the code is organised and why.
5. [Troubleshooting](TROUBLESHOOTING.md).

To re-run the paper's evaluation, see [REPRODUCING.md](../REPRODUCING.md).

## What the first major release does not cover

- Docker environments are declared and exported, not run.
- Isolation is by process and folder within a declared environment, not by
  container.
- Kotlin steps must be vendored in this repository.
- The `in-process` task type in the schema is not supported by the runner.

## Licence

MIT. See [LICENSE](../LICENSE).
