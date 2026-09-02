# Using Steps in Workflows

How to build a workflow from library steps. For the design rationale see
[STEP_LIBRARY.md](STEP_LIBRARY.md); to contribute a step see
[STEP_LIBRARY_CONTRIBUTING.md](STEP_LIBRARY_CONTRIBUTING.md).

## What the library gives you

A curated set of typed analysis steps you compose without writing code. Each step
declares typed input and output ports, so you find one by asking what it consumes
and produces - "what takes `dk.cachet.carp.heartrate` and returns a daily-features
table" - rather than by browsing a taxonomy. The step's implementation language
(Python, R, Kotlin) is an internal detail you never need to know.

Steps are named `<tier>.<subject>.<name>`:

- `core.*` - domain-agnostic data work (`core.stats.summarise`)
- `sensing.*` - tied to one collected data type (`sensing.heartrate.clean`)
- `analysis.*` - derived constructs (`analysis.gait.walking-bouts`)

## Referencing a step

Use a step by reference; do not copy it. The wiring stays in your workflow, the
step's definition stays in the library:

```yaml
steps:
  - uses: "sensing.heartrate.hrv-rmssd"    # no version - resolves to latest
    id: "hrv"
    inputs:
      - id: "clean-heart-rate"
        source:
          type: "step-output"
          stepId: "clean"
          outputId: "clean-heart-rate"
```

At import time the referenced step's task, declared ports and default environment
are pulled in; only the wiring you write locally is applied on top. Keep `uses:`
terse - the consuming workflow should carry what is specific to your pipeline and
nothing the library already knows.

### Versions and reproducibility

A bare `uses:` resolves to the latest version, which is convenient but not by
itself reproducible - the same workflow planned later could pick up a newer step.
Resolution is therefore *recorded* in a lock file so "latest" stays reproducible:

- `steps.lock` sits beside your workflow and is committed. It records, per
  referenced step, the exact resolved id, version and package content hash. Share
  it and two people resolve the same steps; it diffs readably in review when a
  step is upgraded.
- The `ExecutionPlan` (and the execution bundle that carries it) embeds the same
  resolved references, so anyone handed a plan can run it and verify each step
  against the hash it names, without needing your lock file.

To pin a step directly, give an explicit version - `uses: "sensing.heartrate.hrv-rmssd@1.2"` -
which overrides the lock.

## Overriding the environment

A step ships with a default environment but declares what it *requires* of one, so
you can substitute your own (a site mirror, a newer interpreter) and have the
planner check the substitute still satisfies the step. An unsatisfied requirement -
a missing package, an incompatible interpreter - is reported at plan time before
anything runs; a requirement that cannot be verified (a package present but
unpinned) is a warning, not an error.

## Installing the library

The certified library is vendored with the framework by default. If you do not
want the extra checkout, install minimally and let steps resolve from the
registry instead:

```bash
./gradlew build -PcarpDspSteps=false
```

Resolution order is local first, then registry, so a minimal install degrades to
retrieval rather than failing.

## Certified versus community steps

Anyone can publish a step to the registry, so not every published step is
reviewed. **Certified** steps have been reviewed against the contribution
standard and carry a certification marker in their package metadata; **community**
steps carry none. Both are usable - only the first is vouched for.

You can verify a certification yourself: the library publishes a signed manifest
of certified `(id, version, contentHash)` records, and re-hashing a downloaded
package against it confirms the claim without trusting the registry. Filter for
certified steps when the analysis needs to be defensible; use community steps
where convenience matters more than review.
