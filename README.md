# CARP-DSP: CARP Data Science Pipeline

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

CARP-DSP is a workflow framework for mobile and wearable sensing analytics, built
on the CARP study platform. An analysis is a graph of versioned steps with typed
ports. The planner checks a workflow before it runs, including whether a study
protocol collects the data an input needs, and every run leaves a folder that
records what produced each output and can be run again.

## What it does

- **Declarative workflows.** A workflow is a YAML document of steps, environments
  and typed port-to-port connections. Step logic stays in versioned scripts.
- **Plan-time checks.** Formats, CARP data types, missing producers, cycles,
  unresolved environments and protocol data requirements are checked before any
  step runs, with each error placed at its step.
- **A certified step library.** Steps are referenced with `uses:` and pinned by
  content hash in `steps.lock`.
- **Locked environments.** Pixi, Conda, R and the system environment, each
  recorded with what it contained. Docker environments can be declared and
  exported.
- **Run bundles.** Each run folder holds the plan, the report, the environment
  records and an RO-Crate file with the SHA-256 of every file.
- **Exports.** A workflow exports to a CWL Workflow and to Snakemake.

## Repository layout

| Module | Contents |
| --- | --- |
| `carp.dsp.core` | Authoring, YAML codec, linting, planning and execution |
| `carp.dsp.steps` | The certified step library |
| `carp.dsp.demo` | Demo workflows and the evaluation harness |
| `docs/` | Design notes and guides |

## Requirements

- JDK 17
- [Pixi](https://pixi.sh) (v1.0.0 was evaluated with Pixi 0.48.1)
- Docker, only for the Linux runs of the evaluation

## Building v1.0.0

CARP-DSP builds CARP Core and health-workflow-interfaces from source, as sibling
checkouts next to this repository. v1.0.0 was built and evaluated against these
commits:

| Repository | Commit |
| --- | --- |
| [carp.core-kotlin](https://github.com/carp-dk/carp.core-kotlin) (`feature/core-analytics`) | `278d2a57` |
| [health-workflow-interfaces](https://github.com/carp-dk/health-workflow-interfaces) (`main`) | `c332d00a` |

From the folder that contains this repository:

```bash
git clone https://github.com/carp-dk/carp.core-kotlin.git
git -C carp.core-kotlin checkout 278d2a575acc074e3678d4e1ab5fdd3ba220949a
git clone https://github.com/carp-dk/health-workflow-interfaces.git
git -C health-workflow-interfaces checkout c332d00aac0a5b925e88a90104129e1ccbe4a598
cd carp-dsp && ./gradlew build -PskipIntegration
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

## Next steps

- [Quick start](QUICKSTART.md): run a demo and write a first workflow.
- [Reproducing the paper](REPRODUCING.md): re-run every evaluation.
- [Step library](docs/STEP_LIBRARY.md) and [using library steps](docs/STEP_LIBRARY_USAGE.md).
- [Documentation index](docs/README.md).

## Tests and code quality

```bash
./gradlew test                       # all tests
./gradlew :carp.dsp.core:jvmTest     # one module
./gradlew detektPasses               # static analysis
./gradlew koverHtmlReport            # coverage report
```

## Licence

MIT. See [LICENSE](LICENSE).
