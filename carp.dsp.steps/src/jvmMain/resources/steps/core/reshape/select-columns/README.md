# core.reshape.select-columns

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Rename, keep and reorder the columns of a table, without changing any value.

```
table (csv)  ->  selected (csv)
```

## Overview

Source exports rarely use the column names a workflow wants. For example; a Fitbit
export calls the participant `Id` and the hour `ActivityHour`; the library's typed
steps expect `participant_id` and `timestamp`. This step does that translation:
rename columns, keep the ones you need, put them in the order you want.

Reach for it straight after a loader, to put a raw export into the schema a typed
step consumes. It carries no dataset knowledge, and never reinterprets a value,
so it is safe to run on data you have not cleaned yet.

## Data it needs

Any CSV with a header row.

| Column | Type | Meaning                          |
|--------|------|----------------------------------|
| any    | any  | Renamed, kept or dropped by name |

- **Granularity**: none assumed. Works row by row; does not group, sort,
  deduplicate or aggregate.
- **Units**: untouched. A column renamed to `steps` still holds whatever the
  source put there.
- **CARP data types**: none, which places this step in the `core` tier.
- **Missing data**: passed through. An empty cell stays an empty cell.

## What you get

The same rows, with the chosen columns in the chosen order.

| Field                    | Meaning                                              |
|--------------------------|------------------------------------------------------|
| each name in `--columns` | The matching input column, renamed, values unchanged |

From `reference/input.csv`, a Fitbit-shaped export whose `Note` column is dropped:

```csv
participant_id,timestamp,steps
1,2016-04-12 08:00:00,200
1,2016-04-12 09:00:00,
1,2016-04-12 10:00:00,300
2,2016-04-12 08:00:00,500
2,2016-04-12 09:00:00,0
```

Compare the second row with the last: an empty source cell is still empty, and a
literal `0` is still `0`. Missing and zero are different, and this step preserves
the difference rather than deciding it.

## How it works

1. Read the header row.
2. Apply `--rename`.
3. Fail if a `--rename` source is absent, if renaming produces duplicate names,
   or if a `--columns` name does not exist.
4. Resolve the output order; with no `--columns`, keep every column as it comes.
5. Copy each row's chosen fields through as text.

For header `h`, rename map `r` and requested columns `c`:

```
renamed_i = r[h_i] if h_i in r else h_i
keep      = [ index of name in renamed for name in c ]   or all indexes if c is empty
out_row   = [ row[i] for i in keep ]                     each field copied as text
```

## Choices and limits

**Renames are applied before selection**, so `--columns` names columns as they
will appear in the output. This matches `join-tables`, where `--on` also names
keys post-rename.

**Values are never reinterpreted.** The implementation uses the standard-library
`csv` module rather than pandas. A dtype-inferring implementation rewrites values
in its own rendering: `62` becomes `62.0` once a column has a missing cell, long
floats can shift in their last digits, and an empty cell becomes `NaN` or `0`.

**Every mistake is loud.** A mistyped column name stops the step rather than
silently dropping the column, which would surface much further downstream.

**A short row is padded, not rejected.** Missing trailing fields stay empty.

**It does no other reshaping** - no filtering, sorting, deduplication, type
coercion or aggregation.

## Options

| Option      | Default  | Meaning                                                |
|-------------|----------|--------------------------------------------------------|
| `--input`   | required | CSV table with a header row                            |
| `--output`  | required | Output CSV                                             |
| `--rename`  | none     | `old:new,...`, applied before selection                |
| `--columns` | all      | Columns to keep, in output order, named after renaming |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: a relational projection with renaming. The only choice worth
stating is that renames precede selection.

## Implementations

| Language | Path                            |
|----------|---------------------------------|
| Python   | `impl/python/select_columns.py` |

`reference/expected.csv` is produced from `reference/input.csv` by:

```bash
--rename Id:participant_id,ActivityHour:timestamp,StepTotal:steps \
--columns participant_id,timestamp,steps
```

`test_reproduces_the_reference_fixture` runs exactly that and compares against
the published fixture, so the claim is tested rather than asserted. Tests live
beside the implementation:

```bash
python -m pytest impl/python -q
```
