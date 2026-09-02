# Contributing a Step

How to add a step to the library. For the design rationale see
[STEP_LIBRARY.md](STEP_LIBRARY.md); to consume steps in a workflow see
[STEP_LIBRARY_USAGE.md](STEP_LIBRARY_USAGE.md).

Publishing a step to the registry needs no review. Certification - the reviewed,
vouched-for status - does, and that is what this guide is about.

## Scaffold the step

Never copy files by hand:

```bash
./gradlew :carp.dsp.steps:newStep -Pid=sensing.heartrate.hrv-rmssd -Planguage=python
```

`id` is `<tier>.<subject>.<name>` and decides the directory; `language` is one of
`python`, `r`, `kotlin` (default `python`). The task stamps
`templates/<language>/` into `src/jvmMain/resources/steps/<tier>/<subject>/<name>/`
with the ids filled in, refuses to overwrite an existing step, and leaves
`reference/` empty. It writes no certification record - that is a maintainer's
job.

Pick the tier by the objective test, not by taste: `core` if the step references
no CARP data type, `sensing` if it is tied to exactly one, `analysis` if it
produces a derived construct.

## A step directory

```
sensing/heartrate/hrv-rmssd/
  step.yaml           contract: typed ports, environment, metadata
  impl/
    python/  src + tests
    r/       src + tests
  reference/          one fixture every implementation must reproduce
  README.md           what it does, assumptions, limitations, citation
  certification.yaml  added by a maintainer when certifying (not by you)
```

`step.yaml` is the contract; the implementation language is an internal detail. A
different *method* is a different step (`hrv-rmssd` vs `hrv-lf-hf` - separate
contracts, citations, benchmarks). The same method in another *language* is
another implementation of the same step, under `impl/<language>/`, reproducing the
one shared reference fixture.

## Fill it in

1. **Declare the contract** in `step.yaml`. Each port has a `fileFormat`
   (`csv`, `json`) and a `formatRef` - an ontology term for that format. A
   `sensing.*` or `analysis.*` step then declares its typed `fields`: one entry
   per meaningful column or key, each with a `dataType` (the CARP domain type,
   e.g. `dk.cachet.carp.heartrate`) and its own `ontologyRef` for what it means.
   A `core.*` step declares no typed fields - that absence is what the gate
   checks to confirm it is domain-agnostic. A file may carry several typed
   fields; a step tied to more than one data type is `analysis.*`, not
   `sensing.*`.
2. **Choose an environment.** Reuse one from `resources/environments/` if it
   fits - a new one has to be justified in review. Declare what the step
   *requires* of an environment (interpreter, packages), not just which it
   prefers, so a caller can safely substitute.
3. **Implement.** Keep the logic in a function that takes and returns data, so it
   is unit-testable without the filesystem; keep `main()` to argument parsing and
   IO.
4. **Follow the script IO conventions** (below).
5. **Generate the reference fixture** by running the implementation - never
   hand-write it. Keep the input small but include the awkward cases (a missing
   value, an out-of-range value, a gap) so an implementation cannot reproduce it
   by accident.
6. **Test** to the coverage floor, keeping the fixture test that every
   implementation must pass.
7. **Document** assumptions and limitations honestly in the README - reviewers
   read that section most closely.

Run the gate before opening a pull request:

```bash
./gradlew :carp.dsp.steps:validateStepLibrary
```

## Script IO conventions

Implementations follow one calling convention so steps are interchangeable and the
framework wires them without per-step special cases:

- inputs and outputs are named arguments (`--input`, `--output`); position is not
  significant
- multiple ports are addressed by declared port id, not by index
- a step reads only its declared inputs and writes only its declared outputs
- nothing is written outside the step's working directory
- diagnostics go to stderr; stdout is reserved for structured output where a step
  produces any

## Requirements

Two levels, because the framework should not be the bottleneck for sharing.
Community steps go straight to the registry; certified steps meet the full bar.

| Requirement | Certified | Community |
| --- | --- | --- |
| Valid `step.yaml`, plans clean | MUST | MUST (enforced by the registry) |
| Typed fields with declared data types | MUST | MUST |
| Declared environment requirements, satisfied by the chosen environment | MUST | SHOULD |
| Reuses a catalogue environment, or submits one | MUST | - |
| Follows the language template | MUST | - |
| Follows the script IO conventions | MUST | SHOULD |
| Passes the language linter and formatter | MUST | - |
| Inline API docs (KDoc / docstrings / roxygen) on public entry points | MUST | SHOULD |
| Unit tests meeting the coverage floor | MUST | - |
| Reference fixture reproduced by every implementation, verified in CI | MUST | - |
| Benchmark against a published reference implementation where one exists | MUST | - |
| Format term on each port (`formatRef`), ontology term on each typed field (`ontologyRef`) | MUST | SHOULD |
| Discovery metadata complete | MUST | SHOULD |
| Citation for the method it implements | MUST | SHOULD |
| README with assumptions and limitations | MUST | SHOULD |

- **Ontology linkage** is two-level: a `formatRef` on each port names the
  container format, and an `ontologyRef` on each typed field names what that
  field means. Both sit below the step, not on it - more precise and cheaper to
  check.
- **Benchmarking** means reproducing a published reference implementation within a
  stated tolerance on reference data, not a performance number. Where no reference
  implementation exists, the reference fixture is the requirement.

## Discovery metadata

Steps must be indexable for search now and embedding-based retrieval later, so
the metadata is structured fields rather than prose:

- one-sentence summary plus a longer description
- keywords, and tier/subject from the namespace
- format term per port, and per typed field its ontology term and CARP data type
- method name and citation (DOI where available)
- implementation languages available, and the environment requirements
- certification level and review date

The README carries the human narrative; these fields carry the machine index.

## Coverage and tolerance

- **Coverage floor: 85% line, 70% branch**, uniform across languages. Higher than
  the framework's 75/60 because step implementations are small, pure
  transformations with little untestable orchestration.
- **Equivalence tolerance** applies when the same method should give the same
  answer - one implementation versus another, or one platform versus another.
  Per output field: exact for integer and categorical, relative `1e-6` for
  floating point. This is numerical noise, not a change in method.
- **Change between versions is not a tolerance.** If a new version's output
  differs by more than the equivalence tolerance, that is a behaviour change:
  an intended fix or a regression. It requires a version bump, a changelog entry
  saying what changed and why, and an updated reference fixture. For clinical
  measures, state the magnitude against a meaningful-difference benchmark for that
  measure so a consumer can judge whether it affects them - and name the statistic
  (mean, max) you are comparing.

## Review

Review has two halves. The first is automated and blocking; the second is a human
reading the step.

1. **Automated gate** (CI, blocking). Runs as ordinary tests, so `build` enforces
   it: every step decodes, lints and plans with zero errors; ports carry file
   formats, format terms and ontology terms; tier placement matches the declared
   CARP data types; the README carries the standard sections in order; a reference
   fixture and tests are present for every declared implementation; the inlined
   environment matches the catalogue; and the published content hash still matches
   what the step ships.

2. **Human review** (one maintainer, via pull request). Is the method sound, the
   tier and name right, the limitations stated honestly, the citation correct, and
   a new environment justified rather than a catalogue reuse? The reviewer runs the
   step's tests and reads its README as a researcher would.

A step that passes only the first half is still publishable. It is labelled
`gated` rather than `reviewed`, and the difference is visible rather than
enforced.

## Certification

A step's `certification.yaml` records what its label is based on.

```yaml
id: "sensing.heartrate.hrv-rmssd"
version: "1.2"
level: "gated"                 # gated | reviewed
contentHash: "9f2c…"           # sha256 of everything the step ships
reviewedOn: null               # ISO date the reviewing PR was approved
reviewer: null                 # GitHub handle of the approver
reviewedPr: null               # link to that pull request
reviewedHash: null             # contentHash at the moment of review
```

**Two levels, and what each asserts:**

| Level | Asserts |
| --- | --- |
| `gated` | Passes every automated check above. True of each published step by construction |
| `reviewed` | Gated, **and** a named person approved the pull request that last changed it |

The automated gate is a real claim and deserves its own word. With a single
label, a step that is machine-checked but unread by anyone would have to call
itself reviewed, which is not true.

### Why `reviewedHash` is separate from `contentHash`

`contentHash` tracks what the step ships **now**; `certifySteps` refreshes it on
every change. `reviewedHash` records what the reviewer actually read, and is never
recomputed.

Keeping them apart is what stops approval travelling with a step through edits
nobody saw. When they diverge, the step has changed since review and the gate says
so by name -- *"content has changed since @handle reviewed it in PR #142"* -- and
the step must be re-reviewed or dropped back to `gated`.

### Recording a review

Two tasks, run in this order, after the reviewing pull request exists so the link
is real:

```bash
# Refresh the content hash for any step whose files changed
./gradlew :carp.dsp.steps:certifySteps

# Record the approval against one or more steps
./gradlew :carp.dsp.steps:reviewSteps \
    -Previewer=@handle \
    -Ppr=https://github.com/carp-dk/carp-dsp/pull/142 \
    -Psteps=sensing.heartrate.hrv-rmssd      # optional; omit for all
```

Contributors never write the review fields; a maintainer records them on approval.

### Who may review

**The reviewer should not be the author.** During initial development this rule is
suspended: the maintainer setting the library's standard is also writing most of
its steps, so self-approval is recorded honestly rather than pretended away, and
`reviewer` will frequently equal the commit author.

The gate does not enforce this, deliberately -- an author/reviewer check is easy
to satisfy trivially and would give a false sense of independence. It is a policy,
and the record makes compliance auditable: `reviewer` and `reviewedPr` are in the
file, so anyone can see who approved what.

This suspension should be lifted once there is a second maintainer.

Certification renders as a badge in the step README and registry listing, showing
the level the record carries.
