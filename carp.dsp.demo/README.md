# `carp.dsp.demo`

Demo module for running end-to-end CARP-DSP examples on the JVM.

This module provides:
- A shared CLI demo runner (`demo list`, `demo run <id>`)
- Registered JVM demos for real workflows
- A generic workflow runner task for arbitrary workflow YAML files
- Demo-specific resource copying (workflow YAML, scripts, sample data)

## What demos are included

From the current JVM registry:
- `step-execution-demo` - minimal command execution demo
- `diafocus` - blood glucose + daily steps workflow
- `dbdp-covid` - heart-rate + steps biomarker workflow
- `mobgap` - multi-step gait analysis workflow

## Project structure (high level)

- `src/commonMain/kotlin/carp/dsp/demo/DemoMain.kt`
  - shared entrypoint and command dispatcher
- `src/jvmMain/kotlin/carp/dsp/demo/JvmDemoMain.kt`
  - JVM demo registration
- `src/jvmMain/kotlin/carp/dsp/demo/demos/`
  - concrete demo implementations
- `src/jvmMain/resources/workflows/`
  - workflow YAML files used by demos
- `src/jvmMain/resources/scripts/`
  - scripts used by workflow steps
- `src/jvmMain/resources/data/`
  - sample input data files
- `demo_results/`
  - persistent outputs written by workflow demos

## Notes and Documentation

- [Detailed Demo Notes](docs/DEMO_NOTE.md) - Overview of the runtime flow and demo behavior.

## Running demos

From repo root (`carp-dsp`):

```powershell
.\gradlew.bat :carp.dsp.demo:run
```

List demos:

```powershell
.\gradlew.bat :carp.dsp.demo:run -Pargs="list"
```

Run a specific demo:

```powershell
.\gradlew.bat :carp.dsp.demo:run -Pargs="run diafocus"
.\gradlew.bat :carp.dsp.demo:run -Pargs="run dbdp-covid"
.\gradlew.bat :carp.dsp.demo:run -Pargs="run mobgap"
```

Run the generic workflow runner:

```powershell
.\gradlew.bat :carp.dsp.demo:runWorkflow -Pworkflow="C:\path\to\workflow.yaml"
.\gradlew.bat :carp.dsp.demo:runWorkflow -Pworkflow="C:\path\to\workflow.yaml" -Pworkspace="C:\path\to\workspace"
```

## Where outputs go

- Workflow demos (`diafocus`, `dbdp-covid`, `mobgap`) write persistent results under:
  - `carp.dsp.demo/demo_results/<demo-name>/...`
- `step-execution-demo` uses a temporary workspace and deletes it at the end.

## Notes

- The workflow demos copy resources into a local workspace before planning/execution.
- Environment setup is workflow-defined (for example, the Mobgap workflow uses a Pixi environment).
- See `carp.dsp.demo/docs/DEMO_NOTE.md` for a workflow-oriented overview.
