# carp.dsp.steps - Curated step library

Reusable, typed analysis steps that researchers compose into workflows without
writing code, plus the environments they run in.

This file covers layout and local commands only. See also:

- [docs/STEP_LIBRARY.md](../docs/STEP_LIBRARY.md) - design and decisions
- [docs/STEP_LIBRARY_USAGE.md](../docs/STEP_LIBRARY_USAGE.md) - using steps in a workflow
- [docs/STEP_LIBRARY_CONTRIBUTING.md](../docs/STEP_LIBRARY_CONTRIBUTING.md) - adding a step

## Layout

```
carp.dsp.steps/
  src/jvmMain/
    kotlin/carp/dsp/steps/          Kotlin (in-process) step implementations
      core/  sensing/  analysis/
    resources/
      environments/                 environment catalogue - shared, reusable
      steps/                        step content, one directory per step
        core/      io, reshape, stats, viz
        sensing/   heartrate, accelerometer, steps
        analysis/  gait, sleep
  src/jvmTest/
    kotlin/carp/dsp/steps/conformance/   the automated review gate
  templates/                        contributor scaffolds: python, r, kotlin
```

### Environment catalogue

`resources/environments/` holds shared environments, one flat YAML file each. It
stays flat and small on purpose: reuse is the point, and the axes that
distinguish environments (interpreter, package-manager kind, purpose) are
orthogonal, so no single folder hierarchy fits them. The name carries them
instead.

Name environments `env-<interpreter>-<purpose>`, and give each a one-line
`description`:

| id | for |
| --- | --- |
| `env-python-data` | tabular data handling (pandas) |
| `env-python-signal` | numerical / signal processing (numpy, scipy) |
| `env-r-stats` | R statistical analysis |
| `env-system` | no managed environment |

A step reuses a catalogue environment by inlining it under `environments:` in its
`step.yaml`; the gate checks the inlined copy matches the catalogue. Propose a
new environment only when none fits - it has to be justified in review.

### Why implementations are split across two trees

The specification describes a step as one self-contained directory. On disk that
is *almost* true: everything except Kotlin lives together under
`resources/steps/<tier>/<subject>/<step>/`, because Gradle must compile Kotlin
from a source set, not from resources.

So:

| Content | Location |
| --- | --- |
| `step.yaml`, README, reference fixture, certification record | `resources/steps/<tier>/<subject>/<step>/` |
| Python and R implementations | `resources/steps/<tier>/<subject>/<step>/impl/{python,r}/` |
| Kotlin implementation | `kotlin/carp/dsp/steps/<tier>/<subject>/` |

A Kotlin implementation is linked from `step.yaml` by its entry point, the same
way a script implementation is linked by path, so the contract stays the single
source of truth either way.

### A step directory

```
resources/steps/sensing/heartrate/hrv-rmssd/
  step.yaml           contract: typed ports, environment requirements, metadata
  impl/
    python/           implementation + its tests
    r/
  reference/          one fixture every implementation must reproduce
  README.md           what it does, assumptions, limitations, citation
  certification.yaml  review level and the reviewed content hash (certified only)
```

Naming, tier placement and versioning rules are in the specification. In short:
tier is decided by whether the step references a CARP data type; the version
lives in `step.yaml`, never in the path; a different method is a different step,
while the same method in another language is another implementation of the same
step.

## Commands

```bash
./gradlew :carp.dsp.steps:validateStepLibrary   # the conformance gate
./gradlew :carp.dsp.steps:jvmTest               # same, directly
./gradlew :carp.dsp.steps:koverHtmlReport       # coverage (85% line / 70% branch)
```

The gate is ordinary test code, so `./gradlew build` and CI enforce it without
extra wiring.

## Excluding the library

The library is vendored by default. A minimal install skips it and resolves every
`uses:` reference through the registry instead:

```bash
./gradlew build -PcarpDspSteps=false
```

or set `carpDspSteps=false` in `gradle.properties`. Resolution order is local
first, then registry, so a minimal install degrades to retrieval rather than
failing.
