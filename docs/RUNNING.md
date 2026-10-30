# Planning and running

## Running a workflow

```bash
./gradlew :carp.dsp.demo:run -Pargs="list"            # list the demos
./gradlew :carp.dsp.demo:run -Pargs="run mobgap"      # run one
./gradlew :carp.dsp.demo:runWorkflow -Pworkflow="path/to/workflow.yaml" [-Pworkspace="path"]
```

On Windows, use `gradlew.bat`. Demo results go to
`carp.dsp.demo/demo_results/<demo>/`.

## Planning

Nothing runs until the whole workflow has been planned.

1. **Resolve.** `uses:` references are expanded and pinned in `steps.lock`.
2. **Lint.** Ten checks always run: unresolved `uses:` references, duplicate step
   identifiers, duplicate port identifiers within a step, references to missing
   environments, unknown environment kinds, unpinned environments, malformed
   identifiers, missing upstream steps, dependency cycles, and external data
   without attribution. Four more are configurable: naming, missing metadata,
   unused environments and workflow length.
3. **Plan.** The planner flattens the step graph, resolves each step's environment,
   builds the dependency graph from `dependsOn` and the input sources, sorts it
   deterministically, and binds each input to its producer's output. It checks
   that every producer exists and declares the output asked for, that connected
   ports agree in format and data type, and that protocol-bound inputs are
   collected by their protocol.

Lint and plan issues are reported together, each with a severity, a code and a
path into the document, for example
`steps[gsd].dependsOn: references non-existent step 'gait-sequence-detection'`.
Any error stops the run.

## Execution

- **Provisioning.** The run folder receives what the workflow does not carry
  itself: the code of resolved library steps and any input declared by path. Two
  steps that would stage different content at the same path fail the run.
- **Environments.** Each environment is provisioned by its handler and stored
  under an identity derived from its declaration, including its lock file.

  | Kind | Provisioned with | Recorded from |
  | --- | --- | --- |
  | Conda | `conda env create` | `conda list --explicit` |
  | Pixi | `pixi install` (`--frozen` with a lock) | `pixi.lock` |
  | R | system R, optional `renv` lock | `renv.lock`, else R and package versions |
  | System | nothing | operating system only |

- **Steps.** Each step runs in its own process, in its own
  `steps/NN_<name>/{inputs,outputs,logs}` folders, with the run folder as its
  working directory. A declared output that resolves outside `outputs/` is
  rejected before the step runs; a file written outside the step's folders is
  reported as `UNEXPECTED_OUTPUT`.
- **Caching.** A step whose command, environment and inputs are unchanged reuses
  its earlier outputs ([STEP_CACHE.md](STEP_CACHE.md)).

## Step states

A step moves from `PENDING` to `RUNNING`, then to `SUCCEEDED` or `FAILED`. By
default a run halts on the first failure: every step that had not started becomes
`SKIPPED`, and the run reports `FAILED`. The run folder is kept, for diagnosis or
a retry that does not re-run upstream steps.

## The run folder

```
run_<id>/
  workflow.yaml  scripts/  <lock files>
  steps/NN_<name>/
    inputs/  outputs/  logs/
  logs/  environments/
  plan.json  report.json
  ro-crate-metadata.json
```

For each step the report records its start and end time, exit code, command, the
hashes of its outputs, and what its environment contained. See
[SHARING.md](SHARING.md) for what the run folder is used for.
