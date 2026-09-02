# Step Library - Requirements and Design

**Status:** Draft.

This is the design rationale and decision record for the step library (F4). The
two practical guides are separate:

- [STEP_LIBRARY_USAGE.md](STEP_LIBRARY_USAGE.md) - using steps in a workflow
- [STEP_LIBRARY_CONTRIBUTING.md](STEP_LIBRARY_CONTRIBUTING.md) - adding a step

## Purpose

Give researchers a curated set of analysis steps they can compose without writing
code, and a clear path for contributing new ones. Today F4 is the weakest of the
functional requirements: workflows share step *scripts* by relative path within a
repository, so "library" means copy-paste rather than reuse of a published,
versioned unit.

Two things are in scope:

1. **A step library** - a submodule within the carp-dsp repository holding
   curated steps and the environments they run in.
2. **A contribution standard** - what a step must satisfy, and who checks it.

The public registry (wrapping WorkflowHub or Zenodo instead of the local Docker
registry) is a third concern that consumes both, sketched at the end.

## Steps are published as single-step workflows

A step cannot stand alone in the current model: `StepDescriptor.environmentId` is
a key into `WorkflowDescriptor.environments`, and `dependsOn` names sibling
steps. Rather than introduce a second authoring format, a library step **is** a
`WorkflowDescriptor` with one step and its environment, published at
`WorkflowGranularity.TASK`. This reuses the descriptor, linter, `PackageBuilder`,
content hash and CWL export unchanged.

The library therefore also holds a **catalogue of environment descriptors**. A
contributed step reuses a catalogue environment or submits a new one; reuse is
the default, a new environment is justified in review.

## Resolution: write loose, resolve exact, record it

Workflows consume a step by reference (`uses: "sensing.heartrate.hrv-rmssd"`), not
by copying it. A bare reference resolves to the latest version, but "resolve to
latest" and "reproducible" are only compatible if the resolution is *recorded* -
otherwise planning the same workflow later picks up a newer step and produces a
different plan, breaking the determinism the framework measures and claims (NF1).
It would reproduce, at the step level, exactly the silent-drift failure the drift
evaluation demonstrated for analysis libraries.

The standard resolution is what package managers do: the authored document stays
loose, a lock file records the exact versions. This mirrors the framework's own
split between the authored descriptor and the resolved plan, and how environments
already work - a readable spec, an exact lock.

### The lock and the plan compose

`steps.lock` records, per referenced step, the resolved id, version and package
`contentHash`. The hash is what makes it trustworthy rather than merely
informative: `PackageBuilder` already computes a SHA-256 per package, so verifying
it on resolution costs nothing and detects a registry serving different bytes
under the same version.

| Artifact | Committed? | Answers |
| --- | --- | --- |
| `steps.lock` | yes, beside the workflow | "what will this resolve to" - shared and diffed before execution |
| `ExecutionPlan` | no, produced by planning | "what did this run use" - self-contained, executable, verifiable |

The plan embeds the resolved references (so it is self-contained) and records the
hash of the lock it came from (so it traces back to the pinned set). The execution
bundle already carries the plan, so a third party receives the resolved step
identities without any new bundle contents. This closes a real gap: an environment
lock pins the packages a step depends on, but nothing today pins the step's own
script.

## Environment substitution is checked

A caller may override a step's default environment - useful (site mirrors, a newer
interpreter), dangerous (a substitute missing a package). So a step declares what
it *requires* of an environment, and the planner checks a substitute against it.

```yaml
environment:
  default: "env-hr"
  requires:
    kind: ["pixi", "conda"]        # acceptable kinds; omit to accept any
    interpreter:
      name: "python"               # python | r | none
      version: ">=3.11,<4"
    packages:
      - name: "pandas"
        version: ">=2.0"
      - name: "numpy"              # presence only
```

Every field is optional; a step with no `requires` accepts any environment.
Version constraints use comma-separated comparators (`>=3.11,<4`), which read the
same across Python, R and Conda. Dependency entries are `[channel:]name[constraint]`,
matching the demo environments (`pypi:pandas`), so the check parses off the
channel prefix and compares on name.

Because an environment spec often names a package without pinning it, presence is
provable but a version is not. The check reports what it can:

| Situation | Outcome |
| --- | --- |
| Required package absent, or kind/interpreter mismatch | ERROR `ENVIRONMENT_REQUIREMENT_UNSATISFIED` |
| Package present, version pinned, constraint violated | ERROR `ENVIRONMENT_REQUIREMENT_UNSATISFIED` |
| Package present, version unpinned, constraint declared | WARNING `ENVIRONMENT_REQUIREMENT_UNVERIFIED` |
| All requirements provably satisfied | no issue |

This is the same shape as protocol coupling - a declared expectation is an error
when violated, a warning when unverifiable (`PROTOCOL_NOT_VALIDATED`) - so authors
learn one rule. The check runs in the **planner**, not the linter: an unsatisfied
requirement is a run-time failure, which is what the plan-time gate exists to
prevent.

## Organising the library

Neither pure function (`load`, `clean`) nor pure domain (`gait`, `heartrate`)
works alone: `clean` means nothing without knowing what it cleans, and pure domain
duplicates statistics everywhere. The taxonomy is also *not* the discovery
mechanism - the typed data model is; a researcher finds steps by what they consume
and produce. The namespace exists for comprehension, ownership and review.

| Tier | Contains | Test for membership | Examples |
| --- | --- | --- | --- |
| `core.*` | Domain-agnostic data work | References no CARP data type | `core.io.load-csv`, `core.stats.summarise` |
| `sensing.*` | Tied to one collected data type | Consumes or produces a single CARP `DataType` | `sensing.heartrate.clean`, `sensing.steps.daily` |
| `analysis.*` | Derived constructs, often multi-modal | Produces a clinical or behavioural construct | `analysis.gait.walking-bouts`, `analysis.sleep.efficiency` |

The boundary has an **objective test** rather than resting on taste - does the
step reference a CARP data type? - because "is this in the right place" is
otherwise the most bike-sheddable question in a contribution. It maps onto what
exists: `summarise` is `core.*`, the HR features are `sensing.heartrate.*`, the
Mobilise-D chain is `analysis.gait.*`.

## Versioning and layout

**Version does not appear in the directory path.** Every comparable ecosystem
keeps the path stable and the version in metadata: nf-core modules (version in
`meta.yml`), Bioconductor and CRAN (`DESCRIPTION`), Maven (coordinate), OCI images
(`name:tag`). A version directory means every fix spawns a directory and stale
copies accumulate; the registry and VCS tags already solve this. So
`sensing/heartrate/hrv-rmssd/`, version `1.2` in `step.yaml`. A version appears in
a *name* only when incompatible majors must coexist (the Homebrew `python@3.11`
convention), which should be rare enough to be a review decision.

### Method variants versus language variants

**A different method is a different step.** RMSSD and frequency-domain HRV produce
semantically different outputs, cite different papers, benchmark against different
references. As a parameter (`method: rmssd`) one unit would carry several
contracts, citations and benchmarks; so `hrv-rmssd` and `hrv-lf-hf` are separate
steps.

**The same method in a different language is the same step.** The contract - typed
ports, semantics, reference fixture - is identical, and a caller should not care
what is inside. So one directory holds several implementations under
`impl/<language>/`, all reproducing **one** shared reference fixture. That fixture
turns cross-language equivalence into something tested rather than assumed.

## Contribution standard

Two levels: **certified** steps are reviewed and carry a marker in package
metadata; **community** steps publish straight to the registry with none. Both are
usable; only the first is vouched for. This mirrors how nf-core relates to
arbitrary Nextflow pipelines, or Bioconductor to any R package - a curated core
inside a permissive ecosystem.

The requirements table, review process and certification mechanics are in
[STEP_LIBRARY_CONTRIBUTING.md](STEP_LIBRARY_CONTRIBUTING.md). The rationale for the
two decisions most likely to be questioned:

- **Certification is bound to a content hash.** A record names a
  `(step id, version, contentHash)`, so the submodule attests to exactly the
  reviewed artifact. The registry marks a package certified when a matching record
  exists, and a consumer can verify independently by re-hashing what they
  downloaded. This works without the framework controlling the registry, which
  matters when the backend is WorkflowHub or Zenodo: we do not need custody of the
  artifact to vouch for it, only agreement on its bytes. Records aggregate into a
  signed manifest published with each release - no hosted service, which would
  mean running infrastructure to answer a question a static file answers and would
  fail offline.
- **Change between versions is a policy, not a tolerance.** Equivalence tolerance
  (one implementation or platform versus another) is a number - `1e-6`, numerical
  noise. A difference between step *versions* is a behaviour change, which no
  single number can classify as fix or regression; it is governed by a required
  version bump, changelog and updated fixture. This is exactly the drift the
  evaluation captured - the `mobgap` 0.10 to 0.11 upgrade shifted walking speed
  past both the small (~0.05 m/s) and substantial (~0.10 m/s) meaningful-change
  thresholds ([Perera et al. 2006](https://doi.org/10.1111/j.1532-5415.2006.00701.x)) - and the point was not the size of the change but
  that it arrived silently. Requiring the declaration is what a curated library can
  enforce that a general dependency cannot.

## Discovery metadata

Steps must be indexable for faceted search now and embedding-based retrieval
later, which argues for structured machine-readable fields rather than prose (the
field list is in the contributing guide). A README carries the human narrative;
these fields carry the machine index, and are the corpus to embed if step search
later moves to retrieval over natural-language queries.

## Bootstrap set

Target: every demo pipeline composed from library steps rather than inline copies,
migrated incrementally. That converts F4 from an assertion into a demonstration,
and lets the step-reuse evaluation be restated in terms of a published library
rather than a shared directory.

- `core.io.load-csv`, `core.reshape.resample`, `core.reshape.join`,
  `core.stats.summarise`, `core.stats.outliers`, `core.viz.timeseries`
- `sensing.heartrate.clean`, `sensing.heartrate.daily-features`,
  `sensing.heartrate.hrv-rmssd`, `sensing.steps.daily`
- `analysis.gait.sequence-detection`, `analysis.gait.initial-contacts`,
  `analysis.gait.walking-bouts`, `analysis.gait.dmo-aggregate`

Migration order: HR/step pipelines first (already modular), then Mobilise-D, then
the rest.

## Registry (sketch, specified separately)

The current model works against a local Docker registry. Wrapping an existing
repository (WorkflowHub, Zenodo) means treating them as storage and identity
providers behind the existing artifact-package interface: the package format,
content hash and metadata stay ours; DOIs, hosting and preservation come from
them. Certification, protocol references and discovery metadata are package
metadata, so they travel with the artifact whichever backend stores it.

## Settled

- Steps publish as single-step workflows at `TASK` granularity; the library also
  holds an environment catalogue.
- Version lives in metadata, not the path.
- Different method -> different step; different language -> same step, multiple
  implementations, one reference fixture.
- Kotlin steps are in-process framework steps (no environment loading, marginally
  faster). Language is the contributor's choice; the framework tends toward Kotlin.
- Anyone may publish to the registry without review; certification additionally
  requires submission to the submodule, bound to a reviewed
  `(id, version, contentHash)`.
- Certification is a boolean plus a named level (`reviewed` initially).
- `uses:` is written without a version and resolves to latest; the resolution is
  recorded with its content hash in `steps.lock`. An explicit version overrides it.
- Environment-requirement checking runs in the planner, unverifiable constraints
  as warnings.
- Both a committed `steps.lock` and resolved references embedded in the
  `ExecutionPlan`.
- Coverage floor 85% line / 70% branch. Equivalence tolerance `1e-6`; version
  change is a declaration policy.
- The library lives in the carp-dsp repository; contributions arrive as fork pull
  requests, so no write access is needed. Install profiles keep the checkout small
  for those who do not want the vendored set.

## Open

Nothing blocking. Items to settle as implementation reaches them:

1. **Manifest signing mechanism** - git-tagged release to start; sigstore or
   minisign if attestations need verifying independently of GitHub.
2. **Package-constraint parsing** - the `[channel:]name[constraint]` grammar needs
   a precise definition and a small parser, shared by the planner check and CI.
3. **Fragment authoring model** - fold the derivable half of `step.yaml`
   (environment copy, tier/subject, implementation list, reference paths) into a
   compile step so the authored file carries only what is unique. The
   descriptor prerequisite is now in place (ports carry typed `fields`, each with
   its own CARP `dataType` and `ontologyRef`, and the tier check reads the data
   types from there); the compile step itself is discussed but not yet designed.
