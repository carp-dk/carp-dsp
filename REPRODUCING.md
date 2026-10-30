# Reproducing the paper

Every number, generated table and plot in the paper's evaluation (Section 5) and
Appendix B is produced by a Gradle task in `carp.dsp.demo`. None is typed by
hand. The results of the runs reported in the paper are committed in
`carp.dsp.demo/eval_results/`, so you can check them without re-running anything.

## What you need

- JDK 17 (the paper used Temurin 17)
- [Pixi](https://pixi.sh) on your `PATH` (the paper used Pixi 0.48.1)
- Docker, running: the Linux runs, the Snakemake and CWL baselines and the CWL run
  use containers
- CARP Core and health-workflow-interfaces checked out beside this repository at
  the commits in [Building v1.0.0](README.md#building-v100)

The paper's timings come from one laptop (Intel Core i9-12900H, Windows 11); its
Linux runs used a Docker container on the same laptop.

## Run everything

From the repository root:

```bash
./gradlew :carp.dsp.demo:evalAll
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

- The tasks run in order, timings first, so keep the machine otherwise idle.
- The first run downloads the data and installs each environment, which takes a
  while. Later runs reuse them.
- Results go to `carp.dsp.demo/eval_results/`, next to the recorded ones.
- With `-PpaperDir=<path>` the tasks also write their macros and figures into a
  checkout of the paper's LaTeX source. On Windows, write the path with forward
  slashes.

Drift runs on its own, because it installs one environment per `mobgap` version:

```bash
./gradlew :carp.dsp.demo:evalDrift
```

## The evaluations one by one

Run any task on its own as `./gradlew :carp.dsp.demo:<task>`.

| Task | Reproduces |
| --- | --- |
| `evalPaperTimings` | Per-step overhead (Fig. 10) and the script baseline (Fig. 11), Sections 5.2 and 5.3 |
| `evalPlannerScaling` | Plan-time cost against workflow size, Appendix B |
| `evalStaticBaselines` | Snakemake and CWL checks of the injected faults, Section 5.3 |
| `evalStepReuse` | Step reuse and notation cost, Section 5.2 |
| `evalReuse` | Retargeting to a new cohort and collection period, Section 5.2 |
| `evalEnvironmentKinds` | One step per environment kind, Section 5.4 |
| `evalProtocolCoupling` | Protocol linkage, Section 5.3 |
| `evalMobilisedEquivalence` | Equivalence with `mobgap`'s Mobilise-D pipeline, Section 5.2 |
| `evalPlannerDeterminismAll` | Plan determinism, Section 5.3 |
| `evalGaitPortabilityAll` | Outputs across platforms, Section 5.4 |
| `evalBundleRerunAll` | Bundle re-run on Linux, Section 5.5 |
| `evalCwlRunAll` | CWL export run in `cwltool`, Section 5.5 |
| `evalDrift` | Walking-speed drift across `mobgap` versions (Fig. 12), Section 5.4 |

`evalPaperTimings` takes three runs and excludes the first, which installs the
environment. Pass `-Pargs="6"` for more runs.

## Adding your own platform

The recorded cross-platform results cover four platforms: Windows 11 and a Linux
container on the paper's laptop, and macOS (arm64) and Windows Server 2025 on
GitHub-hosted runners (`.github/workflows/portability.yml`). Each platform has its
own CSV in `eval_results/planner-determinism/` and `eval_results/gait-portability/`.

To add your machine, run:

```bash
./gradlew :carp.dsp.demo:evalPlannerDeterminism
./gradlew :carp.dsp.demo:evalGaitPortability
```

Your platform's CSV is written next to the recorded ones, and the comparison
covers every platform present. A machine with the same operating system and
architecture as a recorded one writes to that file, so keep a copy first if you
want to compare against it.

## How outputs are compared

- **Against the reference pipeline,** a stage is bit-for-bit identical when its
  table has the same columns and rows, the same text in every text cell and the
  same value in every numeric cell, with a missing value equal only to a missing
  value.
- **Between runs of the same workflow** (another platform, a bundle, or a CWL
  engine), each output CSV is compared as a file after normalising line endings.
  A file that differs is reported by the largest absolute difference between
  numeric cells in the same position.

No tolerance is set in advance. Small floating-point differences between
processors are expected (Section 5.4).

## Data

- **Use Cases 1 and 2:** `mobgap`'s lab example dataset, which `mobgap` downloads
  on first use. Cohorts MS and HA, participant 001, test 11, trial 1, lower-back
  sensor. The drift runs use all nine recordings.
- **Step-library workflows:** the open Fitbit dataset (Zenodo record 53894,
  CC BY 4.0), fetched by the `core.io.fetch-zenodo` step.
- **Protocol linkage and plan-time cost:** the study protocols and the synthetic
  chains are built by the harness.
- The UbiLife workflows of Appendix C read a CARP database and are not part of
  the evaluation.

## Expected differences

- Timings depend on the machine. The fraction of the pipeline a fault wastes
  depends on the workflow's structure, not the machine.
- The locked gait environment prints a `scikit-learn` `InconsistentVersionWarning`
  on standard error. This is expected and discussed in Section 5.4.
