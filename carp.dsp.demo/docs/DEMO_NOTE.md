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

Inside each run, outputs follow a step-oriented layout similar to:
- `<workflow-name>/run_<uuid>/steps/<step-index>_<step-name>/outputs/`
- `<workflow-name>/run_<uuid>/logs/`

## Running

From repo root:

```powershell
.\gradlew.bat :carp.dsp.demo:run -Pargs="list"
.\gradlew.bat :carp.dsp.demo:run -Pargs="run mobgap"
```

Generic workflow runner:

```powershell
.\gradlew.bat :carp.dsp.demo:runWorkflow -Pworkflow="C:\path\to\workflow.yaml"
```
