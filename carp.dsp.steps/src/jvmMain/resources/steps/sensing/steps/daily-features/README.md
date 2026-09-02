# sensing.steps.daily-features

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Reduce a regularly sampled step-count series to one row per participant per day.

```
steps (csv)  ->  daily-steps (csv)
```

## Overview

This step summarises step-count data at the participant-day level. For each day, it computes descriptive features that
capture overall activity, how that activity is distributed across the day, and the amount of data available to support
those summaries.

Use it to transform interval-level step-count measurements into a format suitable for day-level analyses, such as
tracking trends over time, comparing days within a participant, or joining with daily questionnaires and other daily
features.

## Data it needs

Step counts on a fixed grid, typically the output of `sensing.steps.clean`.

| Column           | Type     | Meaning                            |
|------------------|----------|------------------------------------|
| `participant_id` | string   | Participant identifier             |
| `timestamp`      | datetime | Start of the interval              |
| `steps`          | integer  | Steps counted during that interval |

- **Granularity**: one row per participant per interval, on a fixed grid declared by `--interval` and hourly by default.
  The step checks the data against it and fails on a spacing the interval does not divide.
- **Units**: step counts per interval, not a cadence.
- **CARP data types**: `dk.cachet.carp.stepcount` on both ports, and nothing else, which places this step in the
  `sensing` tier.
- **Missing data**: an empty interval is missing, not zero, and the difference is carried into the output rather than
  resolved.

## What you get

One row per participant-day.

| Field                     | Meaning                             |
|---------------------------|-------------------------------------|
| `participant_id`          | Participant identifier              |
| `date`                    | Calendar date the intervals fall on |
| `total_steps`             | Steps summed over the day           |
| `active_intervals`        | Intervals with at least 100 steps   |
| `observed_step_intervals` | Intervals with a step count present |

From `reference/input.csv`:

```csv
participant_id,date,total_steps,active_intervals,observed_step_intervals
p01,2016-04-12,2650,7,11
p01,2016-04-13,2700,7,8
p02,2016-04-12,5400,8,11
p02,2016-04-13,160,0,8
```

The last row is the one to look at: p02 on 13 April took 160 steps across the whole day with no interval reaching 100,
so `active_intervals` is 0 while
`total_steps` is not. The three fields answer different questions - how much movement, how spread out, and how much of
the day the answer rests on.

## How it works

1. Parse timestamps and check every gap between consecutive samples, per participant, is a positive multiple of
   `--interval`.
2. Take the calendar date of each timestamp.
3. Group by `participant_id` and `date`.
4. Compute the three features within each group.

Written per participant-day, over the intervals `i` falling on that date, with
`a` the activity threshold:

```
total_steps        = sum(steps_i)              empty when nothing was observed
active_intervals   = |{ i : steps_i >= a }|    inclusive; empty likewise
observed_step_intervals = |{ i : steps_i present }| always a number, possibly 0
```

The grid check is what makes the two counts readable. A count of intervals over a threshold is only interpretable if an
interval is a known span of time: the same data at half-hourly spacing yields twice as many intervals, each a lower bar
to clear, and the number would still look reasonable. Declaring the grid turns that from an assumption into something
checked.

## Choices and limits

**The activity threshold is a convention, and it is per interval.** 100 steps in an hour is roughly 1.7 steps/min - a
low bar separating "moved at all" from "did not". It is **not** the ≥100 steps/min moderate-intensity cadence threshold
from the cadence literature, despite the coincidence of the number. Because it applies per interval it means something
different on every grid, so set
`--active-threshold` alongside `--interval` rather than changing one alone.
`analysis.activity.hr-activity-contrast` applies the same rule with the same inclusive comparison, so the two steps
agree on which intervals are active.

**There is no valid-day criterion, deliberately.** Accelerometry practice usually requires a minimum wear time before a
day counts, and applying such a rule here would discard a legitimately sedentary day. Reporting
`observed_step_intervals`
instead lets the consumer apply whatever criterion their protocol specifies. It is also the only field that separates a
quiet day from an unrecorded one: a day spent sitting still and a day the device was barely worn both report a low
`total_steps` and zero `active_intervals`.

**`observed_step_intervals` counts data, not wear.** An interval with a recorded zero counts as observed. A device worn
but not recording, or a gap the cleaning step dropped, does not. It is a denominator for these features, not a wear-time
measure.

**A day with no step data at all reports empty, not zero.** `total_steps` and
`active_intervals` are left empty rather than summed to `0`, which would state that the participant did not move when
the truth is that nothing was recorded.
`sensing.steps.clean` preserves that distinction upstream; this is where it would otherwise be discarded.
`observed_step_intervals` is genuinely `0`, which is the point of having it.

**Dates come from naive timestamps.** The calendar date is read directly off the timestamp with no timezone handling, so
a study spanning zones or a daylight saving transition needs its timestamps normalised beforehand.

## Options

| Option               | Default  | Meaning                                                    |
|----------------------|----------|------------------------------------------------------------|
| `--input`            | required | Step counts on a fixed grid                                |
| `--output`           | required | Output CSV                                                 |
| `--interval`         | `1h`     | Width of one input interval; checked against the data      |
| `--active-threshold` | `100`    | Steps in an interval at or above which it counts as active |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: summing and counting a day's intervals is a standard procedure, and the activity threshold is a
convention chosen for this step rather than a published cut point. See Choices and limits.

## Implementations

| Language | Path                            |
|----------|---------------------------------|
| Python   | `impl/python/daily_step_features.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.csv` within the declared tolerance.
`test_reproduces_the_reference_fixture` runs exactly that, so the claim is tested rather than asserted. Tests live
beside the implementation:

```bash
python -m pytest impl/python -q
```
