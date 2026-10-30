# Documentation

Start with the [README](../README.md) and the [quick start](../QUICKSTART.md).
To re-run the paper's evaluation, see [REPRODUCING.md](../REPRODUCING.md).

## Using CARP-DSP

| Document | Covers |
| --- | --- |
| [Overview](DSP_DOCUMENTATION.md) | The lifecycle, and where to read about each part |
| [Workflow reference](WORKFLOW_REFERENCE.md) | Every part of a workflow document |
| [Planning and running](RUNNING.md) | Checks, execution, step states and the run folder |
| [Using steps in workflows](STEP_LIBRARY_USAGE.md) | Referencing library steps with `uses:` and wiring them |
| [Step cache](STEP_CACHE.md) | When a step is reused instead of run again |
| [Sharing](SHARING.md) | Run bundles, packages, CWL and Snakemake export, registries |
| [Reading CARP's database directly](CARP_DATABASE_COUPLING.md) | The library steps that read a CARP study |
| [Troubleshooting](TROUBLESHOOTING.md) | Common build, environment and plan problems |

## Design

| Document | Covers |
| --- | --- |
| [Architecture](ARCHITECTURE.md) | The domain model, subsystems, modules and design decisions |
| [Step library](STEP_LIBRARY.md) | Tiers, certification, the gate, and Kotlin steps |
| [Protocol coupling](PROTOCOL_COUPLING.md) | Checking an input against the study protocol that collects it |
| [Protocol lineage](PROTOCOL_LINEAGE.md) | Protocol references in package metadata |

## Contributing

| Document | Covers |
| --- | --- |
| [Contributing a step](STEP_LIBRARY_CONTRIBUTING.md) | Adding a step to the library |
| [Contributing](../CONTRIBUTING.md) | Branches, pull requests and checks |
| [Branch protection](BRANCH_PROTECTION_SETUP.md) | Repository settings |
| [Coverage](COVERAGE_SETUP.md) | Coverage reports |

## Releases

- [v1.0.0](release-notes/v1.0.0.md)
