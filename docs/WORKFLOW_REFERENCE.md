# Workflow reference

A workflow is a YAML document in CARP-DSP's workflow language. This page lists
its parts. For a walk-through, see the [quick start](../QUICKSTART.md); for
library steps, see [Using steps in workflows](STEP_LIBRARY_USAGE.md).

## Document

```yaml
schemaVersion: "1.0"
metadata:
  id: "my-analysis"          # stable id; plan identifiers derive from it
  name: "My analysis"        # required
  description: "..."
  version: "1.0"             # default "1.0"
  tags: [ "heart-rate" ]
environments:
  env-py:                    # the key is the environment's id
    name: "py"
    kind: "pixi"
    spec: { ... }
steps:
  - ...
```

## Environments

Every environment has a `name`, a `kind` and a `spec`. Spec values are strings or
lists of strings.

| Kind | Spec keys | Notes |
| --- | --- | --- |
| `pixi` | `pythonVersion`, `dependencies`, `channels`, `lockFile` | With a lock file, the locked versions are installed exactly |
| `conda` | `pythonVersion`, `dependencies`, `channels` | |
| `r` | `rVersion`, `rPackages`, `renvLockFile`, `installationPath` | Uses system R, optionally with an `renv` lock |
| `system` | `dependencies` | No managed environment; pins nothing |
| `docker` | `image` (required) | Declared and exported to CWL and Snakemake; not run |

Defaults: `pythonVersion` 3.11, `channels` conda-forge. For PyPI packages in a
Pixi environment, prefix the name with `pypi:` (for example `pypi:mobgap`). Any key
`env.<NAME>` sets an environment variable for the steps.

The linter warns about an environment that is not pinned.

## Steps

A step is either defined in full or referenced from the library.

### Defined step

```yaml
- id: "gsd"
  metadata: { name: "Gait sequences", version: "1.0" }
  environmentId: "env-py"
  dependsOn: [ "import-data" ]
  task: { ... }
  inputs:  [ ... ]
  outputs: [ ... ]
  cache: true                 # optional; see STEP_CACHE.md
```

### Library step

```yaml
- id: "summarise"
  uses: "core.stats.summarise"
  version: "1.0"              # optional
  dependsOn: [ "daily-steps" ]
  inputs:
    - id: "table"
      source: { type: "step-output", stepId: "daily-steps", outputId: "daily-steps" }
  args: [ "..." ]            # optional argument overrides
```

A library step supplies only wiring: its id, ordering, input sources and argument
overrides. Resolution expands it and pins it in `steps.lock`.

## Tasks

| `type` | Fields | Runs |
| --- | --- | --- |
| `command` | `executable`, `args` | Any program, including Kotlin steps on the task runtime |
| `python` | `entryPoint` (`script` with `scriptPath`, or `module` with `moduleName`), `args` | `python <script>` or `python -m <module>` |
| `r` | `entryPoint` (`r-script` with `scriptPath`), `args` | `Rscript <script>` |

Every task also has a `name`. In `args`, `input.N` and `output.N` stand for the
paths the planner binds to the step's input and output ports, counted from 0.

## Ports

```yaml
inputs:
  - id: "hr"
    descriptor:
      fileFormat: "csv"
      encoding: "utf-8"
      formatRef: "http://edamontology.org/format_3752"
      fields:
        - name: "resting_hr"
          dataType: "dk.cachet.carp.heartrate"
          ontologyRef: "http://purl.bioontology.org/ontology/SNOMEDCT/364075005"
      notes: "..."
    source: { ... }
```

`fileFormat` and the fields' `dataType`s are compared when steps are connected.
Ontology terms are carried for readers and registries and are not compared.
Formats are open strings, so a study can add its own.

### Input sources

| `type` | Fields |
| --- | --- |
| `step-output` | `stepId`, `outputId` |
| `file` | `path` |
| `external` | `uri`, `citation` |
| `protocol` | `protocol` (`id`, `version`, `name`), `dataType` |
| `env-var` | `variableName` |

A `protocol` input is checked against the study protocol: the planner verifies
that the protocol collects the data type ([PROTOCOL_COUPLING.md](PROTOCOL_COUPLING.md)).

### Output destinations

Outputs are written to the step's `outputs/` folder by default. A `destination`
of type `file` (`path`) or `env-var` (`variableName`) overrides this.
