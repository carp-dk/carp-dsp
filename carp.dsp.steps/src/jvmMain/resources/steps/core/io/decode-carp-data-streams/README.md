# core.io.decode-carp-data-streams

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Turn a queried table of CARP data-stream rows into the [CarpTabularCsv] tables.

```
rows (csv)  ->  measurements (csv), provenance (csv)
```

## Overview

CARP stores a data stream as a row of identity - deployment, device role, data
type, first sequence id - and a JSON snapshot holding the measurements. This
step puts the two halves back together and writes the result as the same two
tables `core.io.fetch-study-data` produces from the service.

It pairs with `core.io.query-sql`, which produces the flow:

```
query-sql  ->  rows.csv  ->  decode-carp-data-streams  ->  measurements.csv + provenance.csv
```

## Data it needs

One input: a table with these columns, whatever the query joined to get them.

| Column                | Holds                                               |
|-----------------------|-----------------------------------------------------|
| `study_deployment_id` | the deployment the stream belongs to                |
| `device_role_name`    | the device on that deployment                       |
| `data_type`           | the CARP data type, namespaced                      |
| `first_sequence_id`   | where the sequence starts                           |
| `snapshot`            | the stored JSON: measurements, triggers, sync point |

- **Granularity**: one row per stored sequence, which holds many measurements.
- **Units**: sensor timestamps are microseconds, as CARP records them.
- **CARP data types**: whatever the rows carry. The step declares none, which is
  what places it in the `core` tier.
- **Missing data**: an empty cell in any of the five columns fails the row.

**Order matters.** Sequences of one stream have to arrive in sequence order, so
the query needs `ORDER BY` on the stream and its first sequence id. Without it
the step fails rather than producing a scrambled table.

## What you get

Two tables, joined on `row_id`. The measurement table has one row per measurement, the
first five columns fixed and the value columns the union of the data types
present:

```csv
row_id,data_type,sensor_start_time,sensor_end_time,duration_ms,stepcount.steps
0:0,dk.cachet.carp.stepcount,1000000,,,1000
0:1,dk.cachet.carp.stepcount,2000000,,,2000
```

The provenance table, written only when `--provenance` is given, carries the
deployment, device role, sequence position, trigger ids and sync point for each
`row_id`.

`row_id` numbers what this step read, so it matches the service's only when both
read the same rows: `--from` and `--to` narrow the table after the ids are given
out, leaving gaps where the service would have renumbered.

## How it works

1. Read the input table, taking each row as one stored sequence.
2. Decode the snapshot with carp-core's own JSON, which is what wrote it.
3. Rebuild each sequence from the row's identity and the snapshot's
   measurements, trigger ids and sync point, and append it to one batch.
4. Convert the batch to the CARP data table, narrow it to the window, and write
   both tables.

## Choices and limits

**The window is half-open and in wall time.** `--from` is inclusive, `--to`
exclusive, both epoch milliseconds - matching
`DataStreamService.getBatchForStudyDeployments`, so the same window gives the
same rows whichever path a workflow reads through. A measurement carries the
sensor's own clock, so its sequence's sync point is applied before it is
compared: filtering the raw value would place any device whose clock drifted at
the wrong time.

**The stream identity comes from the row, not the snapshot**, and the two must
agree. A snapshot whose measurements are of a different data type than the
row's column says fails, because a table labelled with the wrong type is worse
than a table that did not arrive.

**A data type this library does not know still arrives.** carp-core wraps an
unresolvable type as `CustomData` rather than failing, and it lands in the
table's generic `value` column. The web service registers several such types -
consent, diagnosis, date of birth - so this is the normal case, not an edge.

**An empty result fails**, whether the input holds no rows or the window matches
none of them. An empty measurement table downstream is much harder to notice.

**Nothing is time-aligned or resampled.** One row per measurement, blank where a
type has no such column.

## Options

| Option           | Default              | Meaning                                    |
|------------------|----------------------|--------------------------------------------|
| `--input`        | required (`input.0`) | The queried data-stream table              |
| `--measurements` | required             | Measurement table to write                 |
| `--provenance`   | none                 | Provenance table; omitted writes none      |
| `--from`         | open                 | Window start in epoch ms, inclusive        |
| `--to`           | open                 | Window end in epoch ms, exclusive          |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: this reverses a storage format. The model it rebuilds:

- CARP Core, `DataStreamBatch` and `Measurement`.
  <https://github.com/cph-cachet/carp.core-kotlin>

## Implementations

| Language | Path                                        |
|----------|---------------------------------------------|
| Kotlin   | `impl/kotlin/main/DecodeCarpDataStreams.kt` |

Kotlin steps are laid out differently from Python ones: `impl/kotlin/main` is a
source root of `carp.dsp.steps` and `impl/kotlin/test` a test source root, so the
classes in the shared task runtime are compiled from exactly the files this step
publishes. Tests run with the module:

```bash
./gradlew :carp.dsp.steps:jvmTest
```

`reference/input.csv` is one stored sequence of two-step counts, and
`reference/expected.csv` the measurement table it becomes - byte for byte the
table `core.io.fetch-study-data` writes for the same measurements, which is the
property worth pinning.
