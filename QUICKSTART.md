# Quick start

This guide builds CARP-DSP, runs a demo workflow, and shows what a workflow looks
like.

## 1. Build

You need JDK 17 and [Pixi](https://pixi.sh). Check out CARP Core and
health-workflow-interfaces beside this repository, as described in
[Building v1.0.0](README.md#building-v100), then:

```bash
./gradlew build -PskipIntegration
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

## 2. Run a demo

List the demos, then run one:

```bash
./gradlew :carp.dsp.demo:run -Pargs="list"
./gradlew :carp.dsp.demo:run -Pargs="run mobgap"
./gradlew :carp.dsp.demo:run -Pargs="run library-minimal"
```

- `mobgap` is the gait analysis of the paper's running example: eight steps in
  one locked Pixi environment.
- `library-minimal` composes four library steps over an open Fitbit dataset.

The first run installs each environment and downloads the data, so it is slower
than later runs. Results go to `carp.dsp.demo/demo_results/<demo>/`.

## 3. What a run leaves behind

Every run folder is a self-contained bundle:

```
run_<id>/
  workflow.yaml  scripts/  <lock files>
  steps/NN_<name>/
    inputs/  outputs/  logs/
  logs/  environments/
  plan.json  report.json
  ro-crate-metadata.json
```

`plan.json` is what was planned, `report.json` what ran, `environments/` what each
environment contained, and `ro-crate-metadata.json` lists every file with its
SHA-256.

## 4. A workflow

A workflow names its steps and connects their ports. Library steps are referenced
with `uses:`, so a workflow only supplies the wiring. This is the start of
`wf-minimal-summary-v2.yaml` in `carp.dsp.demo/src/jvmMain/resources/workflows/`:

```yaml
schemaVersion: "1.0"
metadata:
  id: "wf-minimal-summary-v2"
  name: "Minimal Summary (library steps)"
  version: "2.0"

steps:
  - id: "fetch-steps"
    uses: "core.io.fetch-zenodo"

  - id: "shape-steps"
    uses: "core.reshape.select-columns"
    dependsOn: [ "fetch-steps" ]
    inputs:
      - id: "table"
        source: { type: "step-output", stepId: "fetch-steps", outputId: "file" }
    args:
      - "--rename"
      - "Id:participant_id,ActivityHour:timestamp,StepTotal:steps"
      - "--columns"
      - "participant_id,timestamp,steps"
```

If an input names an output its producer does not declare, or two connected ports
disagree in format or data type, the planner reports it before any step runs.

To run a workflow file of your own:

```bash
./gradlew :carp.dsp.demo:runWorkflow -Pworkflow="path/to/workflow.yaml"
./gradlew :carp.dsp.demo:runWorkflow -Pworkflow="path/to/workflow.yaml" -Pworkspace="path/to/workspace"
```

## Next steps

- [Using library steps](docs/STEP_LIBRARY_USAGE.md) and [the step library](docs/STEP_LIBRARY.md).
- [Reproducing the paper](REPRODUCING.md).
- [Demo notes](carp.dsp.demo/docs/DEMO_NOTE.md).
