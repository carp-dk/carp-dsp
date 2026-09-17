# core.io.fetch-study-data

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Read a CARP study's collected measurements and write them as a table.

```
(no input)  ->  measurements (csv), provenance (csv)
```

## Overview

This step is how a workflow gets at data a study has already collected. The
study, which deployments and device roles to read, which data types and which
time window are all arguments, so the step knows nothing about any particular
study and can be pointed at any of them.

Use it as the first step in a workflow over CARP data, the way
`core.io.fetch-zenodo` starts a workflow over a published dataset. It is the
CARP-side counterpart to that step: same job, different source.

The step talks to a pair of CARP services named by `--services`. Only
`in-memory` exists today, which is carp-core's own in-memory stack - enough to
run a workflow over seeded data, not enough to read a live study. The live pair
arrives with W4-4.

## Data it needs

Nothing on a port; everything the step reads it fetches itself.

- **Granularity**: whatever the study collected. The step does not resample.
- **Units**: the measurement's own. Sensor timestamps are microseconds, as CARP
  records them.
- **CARP data types**: none declared. The step passes `--data-type` through to
  CARP and writes whatever comes back, so it commits to no data type - which is
  what places it in the `core` tier.
- **Missing data**: a request that matches no measurement fails rather than
  writing a header-only file, so an empty result cannot be mistaken for a study
  with nothing in it.

## What you get

Two tables, joined on `row_id`.

The measurement table has one row per measurement. The first five columns are
fixed; the value columns are the union of the data types present, named
`<data type>.<field>` and sorted, so a row carries blanks in the columns
belonging to the other types:

```csv
row_id,data_type,sensor_start_time,sensor_end_time,duration_ms,heartrate.bpm,stepcount.steps
0:0,dk.cachet.carp.stepcount,1000000,,,,1000
1:0,dk.cachet.carp.heartrate,1500000,,,60,
```

The provenance table, written only when `--provenance-output` is given, carries
one row for the same `row_id` with the deployment, device role, sequence
position, trigger ids and sync point it came from:

```csv
row_id,study_deployment_id,device_role_name,data_type,sequence_index,measurement_index,first_sequence_id,trigger_ids,sync_synchronized_on_ms,sync_sensor_timestamp,sync_relative_clock_speed
0:0,4f2b8a10-0000-4000-8000-000000000001,phone,dk.cachet.carp.stepcount,0,0,0,1,0,0,1.0
```

`row_id` is `sequenceIndex:measurementIndex`, which points back into the
`DataStreamBatch` the data came from.

## How it works

1. Merge the targets given as `--target` with any read from `--targets-file`.
2. With no targets, resolve every deployed participant group of `--study-id` and
   read all of them.
3. Group the targets by device role and issue one CARP call per role, merging the
   batches. CARP's query takes deployments and roles as independent sets, so one
   call for `(d1, phone)` and `(d2, watch)` would return all four combinations;
   one call per role keeps the pairs the caller asked for.
4. Convert the batch to the CARP data table and write it in `--format`.

## Choices and limits

**The time window is half-open.** `--from` is inclusive, `--to` exclusive, so a
measurement exactly at the end time is not returned. Both are epoch
milliseconds, while sensor timestamps are microseconds.

**No time alignment.** One row per measurement, blank where a type has no such
column. Resampling or joining on time is a later step's decision, not this
one's.

**No projection beyond `--column`.** Give `--column` to pick value columns;
without it every value column present is written.

**`--refresh-cache` is accepted and does nothing yet.** The flag is part of the
request because the step needs it; the cache belongs with the service-backed
source that W4-4 adds.

**A data type the table writer does not model still lands in the table**, as a
single `value` column holding the measurement's own string form. Dropping it
would lose data the batch carried.

**No credentials.** In-memory services need none. When a remote pair exists, its
base URL and token reach the step through the environment or a file, never as
arguments - arguments are visible in a process listing.

## Options

| Option                | Default                    | Meaning                                                  |
|-----------------------|----------------------------|----------------------------------------------------------|
| `--study-id`          | required                   | Study to read                                            |
| `--output`            | required                   | Measurement table path                                   |
| `--provenance-output` | none                       | Provenance table path; omitted writes none               |
| `--target`            | none                       | Repeatable `<deployment-id>[:<role>]`; omitted reads all |
| `--targets-file`      | none                       | One target per line, `#` comments allowed                |
| `--data-type`         | none                       | Repeatable namespaced type; omitted reads every type     |
| `--from`              | open                       | Window start in epoch ms, inclusive                      |
| `--to`                | open                       | Window end in epoch ms, exclusive                        |
| `--column`            | every value column         | Repeatable value column to keep                          |
| `--format`            | `csv`                      | Output format                                            |
| `--refresh-cache`     | off                        | Re-read rather than serve a previous read                |
| `--services`          | `in-memory`                | Which CARP services to read through                      |

Override these per use with `args:` on a `uses:` reference - the defaults in
`step.yaml` name no real study, so `--study-id` always has to be supplied that
way.

## References

No method paper: this is a service query and a table write. The data model it
reads is CARP's own:

- CARP Core, `DataStreamService.getBatchForStudyDeployments`.
  <https://github.com/cph-cachet/carp.core-kotlin>

## Implementations

| Language | Path                                    |
|----------|-----------------------------------------|
| Kotlin   | `impl/kotlin/main/FetchCarpStudyData.kt` |

Kotlin steps are laid out differently from Python ones: `impl/kotlin/main` is a
source root of `carp.dsp.steps` and `impl/kotlin/test` is a test source root, so
the classes in the shared task runtime are compiled from exactly the files this
step publishes. Tests therefore run with the module rather than with a language
tool of their own:

```bash
./gradlew :carp.dsp.steps:jvmTest
```

`reference/expected.csv` is the measurement table for a deployment carrying two
step-count measurements. A step that reads a service has no file input to fix,
so the fixture is pinned by `FetchStudyDataFixtureTest`, which seeds exactly
that deployment in the in-memory services and asserts the step reproduces the
published file.
