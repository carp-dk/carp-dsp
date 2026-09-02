# core.stats.summarise

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Report count, mean, median, standard deviation, minimum and maximum for every
numeric column of a table.

```
table (csv)  ->  summary (json)
```

## Overview

Before analysing a dataset you usually want to know what is in it: how many rows
survived the pipeline, whether a column is on the scale you expected, and how
much of it is missing. This step answers that for any table, without being told
what the columns mean.

Reach for it as a checkpoint - after loading, after cleaning, or at the end of a
workflow as a machine-readable record of what was produced. Because the output is
JSON with a fixed shape, it can be compared between runs or asserted on, which a
printed summary cannot.

## Data it needs

Any CSV with a header row. The step makes no assumption about the columns.

| Column | Type | Meaning |
| --- | --- | --- |
| any | numeric | Summarised |
| any | text, date, boolean | Ignored, including when named in `--columns` |

- **Granularity**: none assumed. The step summarises rows as they are - it does
  not group, so summarising a per-participant-day table gives cohort-wide
  statistics, not per-participant ones.
- **Units**: whatever the input carries. The step reports numbers without units
  and cannot state them for you.
- **CARP data types**: none. Operating on any table regardless of what it
  measures is what places this step in the `core` tier.
- **Missing data**: excluded per column. Each column's `n` reports how many
  values it actually had, against `n_rows` for the table.

## What you get

A JSON object with the table's row count and one entry per numeric column,
columns in sorted order so the output is stable between runs.

| Field | Meaning |
| --- | --- |
| `n_rows` | Rows in the table, including rows with missing values |
| `columns.<name>.n` | Non-missing values in that column |
| `columns.<name>.mean` | Arithmetic mean of the non-missing values |
| `columns.<name>.median` | 50th percentile |
| `columns.<name>.sd` | Sample standard deviation; `null` when `n <= 1` |
| `columns.<name>.min` | Smallest value |
| `columns.<name>.max` | Largest value |

From `reference/input.csv`, a five-row table where one `steps` value is blank:

```json
{
  "columns": {
    "steps": {
      "max": 12980.0,
      "mean": 8933.0,
      "median": 9321.0,
      "min": 4110.0,
      "n": 4,
      "sd": 3759.8434364567
    },
    "resting_hr": {
      "max": 66.0, "mean": 59.4, "median": 58.0,
      "min": 52.0, "n": 5, "sd": 5.6391488719
    }
  },
  "n_rows": 5
}
```

The pair to read together is `n_rows: 5` against `steps.n: 4` - one row had no
step count, so the mean is over four values while `resting_hr`'s is over five.
Comparing the two tells you how complete each column is, which is often the
reason to run this step at all. Note also that the `label` column is absent:
text columns are not summarised, so a missing column name means non-numeric, not
an error.

## How it works

1. Read the table.
2. Select the numeric columns. If `--columns` was given, keep only those named -
   non-numeric names are silently ignored rather than failing.
3. For each selected column, drop missing values and compute the statistics.
4. Round every floating-point result to 10 decimal places and emit sorted JSON.

Over the non-missing values `x_1 ... x_n` of one column:

```
n      = count(x_i)                       missing values excluded
mean   = sum(x_i) / n
median = quantile(x, 0.5)                 linear interpolation between order statistics
sd     = sqrt( sum((x_i - mean)^2) / (n - 1) )    ddof = 1; null when n <= 1
min    = min(x_i)
max    = max(x_i)

n_rows = number of rows in the table, missing values included
```

`sd` divides by `n - 1`, the sample standard deviation, so it estimates a wider
population rather than describing only the rows present.

## Choices and limits

**It does not group.** Every statistic is over the whole table. Run it on a
per-participant subset if you want per-participant numbers, or aggregate first.
This is easy to miss when the table happens to have a `participant_id` column,
which is summarised only if it is numeric.

**An identifier that looks numeric will be summarised.** A participant column
holding `1, 2, 3` is a number as far as this step is concerned, and the mean of
it is meaningless. Use `--columns` to restrict the output when that matters.

**Rounding is for stability, not precision.** Ten decimal places keeps output
byte-identical across platforms so it can be compared between runs. It is not a
claim about how precise the underlying values are.

**`sd` is `null`, not zero, for a single value.** One observation carries no
information about spread, which is not the same as no spread.

**Missing values are excluded, not counted separately.** The only signal about
missingness is `n` against `n_rows`, and only for numeric columns. A text column
that is entirely blank is invisible here.

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `--input` | required | CSV table with a header row |
| `--output` | required | JSON summary output path |
| `--columns` | all numeric | Comma-separated subset; non-numeric names are ignored |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: these are standard descriptive statistics. The two choices
worth stating are `ddof = 1` for the standard deviation and linear-interpolation
quantiles for the median, both being the common defaults.

## Implementations

| Language | Path |
| --- | --- |
| Python | `impl/python/summarise.py` |

Every implementation must reproduce `reference/expected.json` from
`reference/input.csv` within the declared tolerance. Tests live beside the
implementation:

```bash
python -m pytest impl/python -q
```
