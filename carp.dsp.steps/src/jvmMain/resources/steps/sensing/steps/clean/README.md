# sensing.steps.clean

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Put a step-count series onto a regular time grid, marking values that cannot be
true so later steps can see which readings to trust.

```
raw-steps (csv)  ->  clean-steps (csv)
```

## Overview

Wearable step exports arrive irregularly: timestamps repeat, intervals go
missing when the device was off or not worn, and occasional readings are simply
impossible. This step puts a participant's series onto an even grid and marks bad
readings as empty, without repairing or inventing anything.

Reach for it as the first step after loading step counts, before any aggregation.
Most downstream steps assume evenly spaced intervals, and aggregating an
irregular series silently weights some periods more than others.

## Data it needs

| Column | Type | Meaning |
| --- | --- | --- |
| `participant_id` | string | Participant identifier; each is gridded independently |
| `timestamp` | datetime | Start of the interval the count covers |
| `steps` | integer | Steps counted during that interval |

- **Granularity**: any regular or irregular interval. The output grid is set by
  `--interval` (hourly by default); pick one that matches your source, since a
  finer grid than your data will produce mostly empty intervals.
- **Units**: counts per interval, not a rate.
- **CARP data type**: `dk.cachet.carp.stepcount` on both ports, and nothing else -
  which is what places this step in the `sensing` tier.
- **Missing data**: preserved as missing. Nothing is filled.

## What you get

The same three columns, on a continuous grid per participant.

| Field | Meaning |
| --- | --- |
| `participant_id` | Unchanged |
| `timestamp` | On the requested grid, continuous from that participant's first to last reading |
| `steps` | Nullable integer; **empty** where the interval was missing or the reading was masked |

From `reference/input.csv`, where the 11:00 reading was `-50` and p02 had no
12:00 reading at all:

```csv
participant_id,timestamp,steps
p01,2024-01-01 08:00:00,300
p01,2024-01-01 09:00:00,400
p01,2024-01-01 10:00:00,500
p01,2024-01-01 11:00:00,
p01,2024-01-01 12:00:00,700
```

The empty cell at 11:00 is the point of the step. A reading of `-50` is wrong,
but *how* wrong is unknown - so the value is withheld rather than replaced with a
guess. Downstream, that interval is excluded from means rather than dragging them
toward zero.

## How it works

Applied in this order; the order matters, since masking before gridding keeps bad
values out of the grid.

1. Parse timestamps and coerce `steps` to numeric. Anything unparseable becomes
   missing.
2. Mask negative counts.
3. Mask counts above `--max-steps`, if a ceiling was given.
4. Per participant: sort by time and reduce duplicate timestamps to their first
   non-missing reading.
5. Per participant: resample onto a grid at `--interval`, **summing** the
   readings that fall in each interval. Intervals with no reading are empty, not
   zero. `--drop-gaps` removes them instead of emitting them.
6. Emit `steps` as a nullable integer, so counts stay counts despite the gaps.

```
steps_t = sum(steps_i | t <= timestamp_i < t + interval)
        = missing  when the interval contains no reading
```

## Choices and limits

**Missing is never zero, and this is the step's main purpose.** An interval with
no reading is not an interval with no steps. Filling gaps with zero
underestimates activity, and it destroys the signal non-wear detection depends
on: a run of zeros is ambiguous between "device not worn" and "person sat still",
which is exactly why algorithms such as Troiano and Choi exist. A step that
asserts zero has already discarded the evidence those methods need.

**Negative counts are masked, not clipped to zero,** on the same reasoning: a
negative count means the reading is wrong, not that no steps were taken.

**Readings finer than the grid are summed, not sampled.** Give it minute-level
data on an hourly grid and each hour reports the hour's total. The output grid is
aligned to the interval, so it joins with another signal cleaned to the same one.

**There is no plausibility ceiling by default, and the value is not a standard.**
`--max-steps` is off unless you set it. As a bound: wearable step detection
operates to roughly 150 steps/min, and ≥130 steps/min is already vigorous
intensity, so a full hour at that rate is about **9,000 steps** - an outer limit
on what a device could plausibly report for an hour, not a threshold for normal
activity. The right value depends on your interval, device and population, and a
ceiling set too low silently deletes real data.

**It does not detect non-wear time.** Non-wear detection is a separate, validated
problem operating on minute-level data, not the hourly summaries this step is
usually given. Use a dedicated step, not this one.

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `--input` | required | Raw step-count CSV |
| `--output` | required | Output CSV |
| `--interval` | `1h` | Grid interval |
| `--max-steps` | off | Plausibility ceiling per interval; counts above it are masked |
| `--drop-gaps` | off | Remove empty intervals instead of emitting them |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: deduplication and time-gridding are bookkeeping rather than a
published method. The optional ceiling is derived from cadence ranges reported in
the CADENCE-Adults studies rather than taken from a validation study, and is a
configurable default with no clinical standing:

- [CADENCE-Adults, 21-60 years](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7877025/)
- [CADENCE-Adults, 61-85 years](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10688086/)

For the non-wear algorithms referred to above:

- [Comparison and validation of wear/non-wear time algorithms](https://link.springer.com/article/10.1186/s12874-019-0712-1)
- [`actigraph.sleepr`](https://github.com/dipetkov/actigraph.sleepr) - reference implementations of Troiano and Choi

## Implementations

| Language | Path |
| --- | --- |
| Python | `impl/python/clean_steps.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.csv` within the declared tolerance. Tests live beside the
implementation:

```bash
python -m pytest impl/python -q
```
