# Demo Module Note

This note explains what the `carp.dsp.demo` module does and how the demo flow works.

## Purpose

`carp.dsp.demo` is a runnable showcase module for CARP-DSP workflows. It is intended to:
- demonstrate wiring from workflow YAML -> imported definition -> execution plan -> runtime execution
- provide concrete examples with sample data and scripts
- make manual validation and debugging easier by printing outputs and storing run artifacts

## How it works

At runtime, the flow is:
1. `DemoMain.main` is called
2. `registerPlatformDemos()` registers JVM demos
3. CLI dispatch resolves `list` or `run <id>`
4. Selected demo sets up workspace/resources
5. Workflow YAML is decoded and imported
6. Planner builds and validates execution plan
7. Executor runs steps and writes artifacts/logs
8. Demo prints key result summary to stdout

## Current demos

- `step-execution-demo`
  - tiny command execution example
  - temp workspace is deleted when done
- `diafocus`
  - loads mock BGM + steps data and computes summary metrics
  - **Scripts:**
    - `load_bgm.py`: Extracts blood glucose data from mock JSON.
    - `load_steps.py`: Extracts step count data from mock JSON.
    - `bgm_steps_analysis.py`: Analyses glucose metrics (time-in-range) and daily step trends.
- `dbdp-covid`
  - analyses resting HR + steps and produces biomarker flag
  - **Scripts:**
    - `load_hr_steps.py`: Pre-processes heart rate and steps CSV data.
    - `covid_hr_steps.py`: Detects deviations (elevated HR, reduced steps) as potential biomarkers.
    - `report_biomarker.py`: Generates a human-readable summary of the detection results.
- `mobgap`
  - IMU gait pipeline with sequence detection, IC detection, parameter estimation, walking-bout assembly, aggregation, and plotting
  - **Scripts:**
    - `import_data.py`: Fetches and prepares the LabExampleDataset.
    - `gsd.py`: Gait Sequence Detection - identifies gait regions in IMU data.
    - `icd.py`: Initial Contact Detection - detects and labels individual steps.
    - `per_sec_params.py`: Estimates per-second gait parameters (speed, cadence, etc.).
    - `wba.py`: Walking Bout Assembly - filters and groups strides into valid bouts.
    - `aggregate.py`: DMO Aggregation - computes summary statistics across bouts.
    - `plot_wb_params.py`: Visualizes walking bout metrics over time.
    - `plot_aggregated_dmos.py`: Generates bar charts of final aggregated DMOs.
- `hr-activity`
  - activity summary over the open Fitbit HR + steps dataset (Zenodo 53894), built from the shared `hr_lib` step library
  - **Scripts** (`scripts/hr_lib/`): `load_hr_steps.py`, `clean_resample.py`, `daily_features.py`, `summarise.py`, `detect_anomaly.py`, `visualise.py`

## Evaluation harnesses

Paper evaluation harnesses (Section 8 of the system paper) also register in the demo menu under the `eval` category. Each writes its results to `carp.dsp.demo/eval_results/`.

- `planner-determinism-eval`: plans the mobgap workflow N times (default 100) and verifies plan determinism (fixed namespace). Optional arg: iteration count.
- `planner-scaling-eval`: times decode/import/plan/validate over synthetic linear chains of 2-200 steps (Fig E), then rebuilds `fig-planner-scaling.pdf` via the eval pixi env (`scripts/eval/pixi.toml`, task `plot-scaling`; system Python fallback). Optional args: comma-separated sizes, repeats.
- `error-detection-eval`: plans 5 fault-injected mobgap workflows and records plan-time detection (Fig A, CARP arm). The ad-hoc arm and figure: `./gradlew :carp.dsp.demo:evalErrorDetectionFull`.
- `step-reuse-eval`: measures step reuse across the 3 HR/step workflows built from the shared 6-step library.
- `mobgap-timed-eval`: instrumented Use Case 1 run - phase and per-step timings, output hashes (Fig D). Optional arg: run count.
- `dependency-drift-eval`: runs the walking-speed pipeline under 7 mobgap versions to measure output drift (Fig B). Slow on first run (pixi solves one env per version); requires pixi on PATH.
- `protocol-coupling-eval`: plans `protocol-coupling-mixed.yaml` - one input collected by a study protocol, one open dataset - against two protocol snapshots and with no protocol at all, showing plan-time `PROTOCOL_DATA_NOT_COLLECTED` rejection, a clean pass, and the `PROTOCOL_NOT_VALIDATED` warning (F5). Fixtures: `resources/workflows/protocol-coupling-mixed.yaml`, `resources/protocols/*.json`. No live study or protocol service needed.
- `run-all-evals`: runs all of the above in sequence.

Gradle-only (not in the menu): `evalPortability` - cross-distro output determinism via Docker; requires Docker running.

Each eval also has a direct gradle task (`evalPlannerDeterminism`, `evalPlannerScaling`, `evalErrorDetection`, `evalReuse`, `evalStepReuse`, `evalMobgapTiming`, `evalProtocolCoupling`; args via `-Pargs="..."`).

## Shared IO helpers

Demos and evals share `carp.dsp.demo.io.DemoIo` for filesystem and classpath access:

- `projectRoot()` - the `carp.dsp.demo` module root on disk
- `loadResource(path)` - read a bundled resource as text
- `copyResource(resourcePath, target)` - copy a resource into a workspace
- `evalResultsDir()` - `<module>/eval_results`, created if absent
- `demoResultsDir(name)` - `<module>/demo_results/<name>`

Use these rather than re-deriving paths from `protectionDomain`/`classLoader`. Because all output paths resolve from the module root, results land in the same place regardless of the working directory a demo is launched from.

## Inputs and resources

Demos typically use:
- workflow YAML: `src/jvmMain/resources/workflows/`
- scripts: `src/jvmMain/resources/scripts/`
- sample data: `src/jvmMain/resources/data/`

Before execution, demos copy required resources into a workspace path used by the DSP engine.

## Outputs and logs

Persistent workflow demo results are written under:
- `carp.dsp.demo/demo_results/diafocus/`
- `carp.dsp.demo/demo_results/dbdp_covid/`
- `carp.dsp.demo/demo_results/mobgap/`
- `carp.dsp.demo/demo_results/hr_activity/`

Evaluation results (CSVs, txt reports, figures) go to `carp.dsp.demo/eval_results/`.

Inside each run, outputs follow a step-oriented layout similar to:
- `<workflow-name>/run_<uuid>/steps/<step-index>_<step-name>/outputs/`
- `<workflow-name>/run_<uuid>/logs/`

## Running

From repo root:

```powershell
.\gradlew.bat :carp.dsp.demo:run -Pargs="list"
.\gradlew.bat :carp.dsp.demo:run -Pargs="run mobgap"
.\gradlew.bat :carp.dsp.demo:run -Pargs="run planner-scaling-eval"
.\gradlew.bat :carp.dsp.demo:run -Pargs="run run-all-evals"
```

Generic workflow runner:

```powershell
.\gradlew.bat :carp.dsp.demo:runWorkflow -Pworkflow="C:\path\to\workflow.yaml"
```
