# core.reshape.join-tables

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Join two tables on shared keys, optionally renaming columns and reducing one side
onto a coarser time grid first.

```
left (csv) + right (csv)  ->  joined (csv)
```

## Overview

Two signals from the same study rarely arrive ready to join: one export calls the
participant `Id` and the other `subject`, and one samples every twenty minutes
while the other reports hourly totals. This step handles all three problems in
one place - rename columns, put one side on the other's time grid, then join.

Reach for it whenever a workflow needs to combine two sources, particularly
before an analysis step that requires both signals in one table. It carries no
knowledge of any dataset: every column name, key and aggregation is an argument,
so the same step serves any pair of tables.

## Data it needs

Two CSVs with header rows, sharing at least one column after renaming.

| Column | Type | Meaning |
| --- | --- | --- |
| join keys | any | Named by `--on`; must exist on both sides after renaming |
| time column | datetime | Only when using `--left-resample` / `--right-resample` |
| everything else | any | Carried through; colliding names get `_x` / `_y` suffixes |

- **Granularity**: any. Differing granularity between the two sides is exactly
  what the resample options are for - reduce the finer side to the coarser grid
  before joining.
- **Units**: unchanged. Aggregating a rate with `sum` will silently produce
  nonsense; the step cannot check this for you.
- **CARP data types**: none. Operating on any pair of tables regardless of what
  they measure is what places this step in the `core` tier.
- **Missing data**: no special handling. Rows are matched or not by `--how`; a
  missing value inside a non-key column is carried through as-is.

## What you get

One CSV: the two tables joined, left-hand columns first.

| Field | Meaning |
| --- | --- |
| join keys | One column per `--on` key |
| left columns | All remaining left-hand columns, or those named in `--left-agg` if resampled |
| right columns | The same for the right-hand side |

From `reference/left.csv` (hourly step totals, columns `Id, ActivityHour,
StepTotal`) and `reference/right.csv` (heart rate roughly every twenty minutes,
columns `Id, Time, Value`), with both sides renamed and the right resampled
hourly with `heart_rate_bpm:mean`:

```csv
participant_id,timestamp,steps,heart_rate_bpm
1,2016-04-12 08:00:00,200,60.666666666666664
1,2016-04-12 09:00:00,250,62.666666666666664
1,2016-04-12 10:00:00,300,64.66666666666667
```

The first row's `60.666...` is the mean of the three heart-rate readings that
fell inside the 08:00 hour (60, 61, 61). Note the timestamps are **floored**, not
rounded: a reading at 08:45 belongs to the 08:00 hour, not the 09:00 one.

## How it works

1. Read both tables and apply `--left-rename` / `--right-rename`, each a
   comma-separated list of `old:new` pairs.
2. Where a side has a resample spec `timecol:rule`, floor that column to `rule`,
   group by the join keys, and aggregate using that side's `--agg` map.
3. Reconcile key dtypes: if either side's key is a datetime, parse both as
   datetimes; otherwise cast both to string.
4. Merge on `--on` using `--how`.

The resample, for one side with time column `t`, rule `r` and aggregation map
`f`:

```
t'_i = floor(t_i, r)

out[k, c] = f_c( { x_ic : row i has keys k } )   for each column c named in --agg
```

Then the join, over key tuples `k`:

```
inner  = { k : k in left AND k in right }
left   = { k : k in left }
right  = { k : k in right }
outer  = { k : k in left OR  k in right }
```

Unmatched rows under `left`, `right` or `outer` keep their own columns and get
missing values for the other side's.

## Choices and limits

**Resampling drops every column not named in `--agg`.** The aggregation map is
the whole output schema for that side - a column you forget to name disappears
without a warning. Include the key columns' companions deliberately.

**Timestamps are floored, not rounded, and the rule is not validated.** Flooring
means a reading is assigned to the interval it started in. An unparseable or
unintended offset alias fails inside pandas rather than with a helpful message.

**The aggregation function is your responsibility.** `sum` over a rate, or `mean`
over a cumulative counter, produces a number the step will happily write. Nothing
here knows what the columns measure.

**Key dtype reconciliation is a blunt instrument.** When key types differ and
neither is a datetime, both are cast to string - so `1` and `01` stay different,
and `1` and `1.0` become different too. Normalise identifiers before this step if
your two sources format them differently.

**Colliding non-key columns are suffixed, not merged.** Two sides both carrying
`value` produce `value_x` and `value_y`. Rename first if you want something
readable.

**An inner join loses rows silently.** The default `--how inner` keeps only
matched keys, and the step reports the row counts to its log but does not fail on
a large drop. Check the counts when a join produces less than you expected.

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `--left` | required | Left-hand CSV |
| `--right` | required | Right-hand CSV |
| `--output` | required | Output CSV |
| `--on` | required | Comma-separated join keys, after renaming |
| `--how` | `inner` | `inner`, `left`, `right` or `outer` |
| `--left-rename` | none | `old:new,...` applied before anything else |
| `--right-rename` | none | As above, for the right |
| `--left-resample` | none | `timecol:rule`, e.g. `timestamp:1h` |
| `--right-resample` | none | As above, for the right |
| `--left-agg` | none | `col:func,...` applied after the left resample |
| `--right-agg` | none | As above, for the right |

Override these per use with `args:` on a `uses:` reference - this step is
designed to be configured that way rather than used as it ships.

## References

No method paper: this is a relational join with an optional time-bucketed
aggregation, both standard operations.

## Implementations

| Language | Path |
| --- | --- |
| Python | `impl/python/join_tables.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/left.csv` and `reference/right.csv` within the declared tolerance.
Tests live beside the implementation:

```bash
python -m pytest impl/python -q
```
