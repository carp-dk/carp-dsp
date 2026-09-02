# sensing.heartrate.daily-features

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Reduce a regularly sampled heart-rate series to one row per participant per day.

```
heart-rate (csv)  ->  daily-heart-rate (csv)
```

## Overview

This step summarises heart-rate data at the participant-day level. For each day, it computes descriptive features that
capture the distribution of heart rate values and the amount of data available to support those summaries.

Use it to transform interval-level heart-rate measurements into a format suitable for day-level analyses, such as
tracking trends over time, comparing days within a participant, or joining with daily questionnaires and other daily
features.

## Data it needs

Heart rate on a fixed grid, typically the output of `sensing.heartrate.clean`.

| Column           | Type     | Meaning                            |
|------------------|----------|------------------------------------|
| `participant_id` | string   | Participant identifier             |
| `timestamp`      | datetime | Start of the interval              |
| `heart_rate_bpm` | float    | Mean heart rate over that interval |

- **Granularity**: one row per participant per interval, on a fixed grid declared by `--interval` and hourly by default.
  The step checks the data against it and fails on a spacing the interval does not divide.
- **Units**: beats per minute.
- **CARP data types**: `dk.cachet.carp.heartrate` on both ports, and nothing else, which places this step in the
  `sensing` tier.
- **Missing data**: absent intervals do not contribute. There is no completeness requirement; `observed_hr_intervals`
  reports what the day rested on.

## What you get

One row per participant-day.

| Field                   | Meaning                                             |
|-------------------------|-----------------------------------------------------|
| `participant_id`        | Participant identifier                              |
| `date`                  | Calendar date the intervals fall on                 |
| `mean_hr`               | Mean heart rate across the day's intervals          |
| `resting_hr`            | 5th percentile of the day's heart rates - see below |
| `peak_hr`               | Highest interval mean heart rate that day           |
| `observed_hr_intervals` | Intervals with a heart rate present                 |

From `reference/input.csv`:

```csv
participant_id,date,mean_hr,resting_hr,peak_hr,observed_hr_intervals
p01,2016-04-12,70.49197860962566,63.0,76.0,11
p01,2016-04-13,70.0,63.7,77.0,8
p02,2016-04-12,75.49197860962566,68.0,81.0,11
p02,2016-04-13,75.0,68.7,82.0,8
```

`peak_hr` is the highest *interval mean*, so it is lower than any peak the device itself recorded - the averaging inside
the interval has already removed it. The 13 April rows rest on eight intervals rather than eleven, which is what
`observed_hr_intervals` is there to say.

## How it works

1. Parse timestamps and check every gap between consecutive samples, per participant, is a positive multiple of
   `--interval`.
2. Take the calendar date of each timestamp.
3. Group by `participant_id` and `date`.
4. Compute the four features within each group.

Written per participant-day, over the intervals `i` falling on that date, with
`q` the resting quantile:

```
mean_hr            = mean(hr_i)
resting_hr         = quantile(hr_i, q)       linear interpolation between order statistics
peak_hr            = max(hr_i)
observed_hr_intervals = |{ i : hr_i present }|  always a number, possibly 0
```

`resting_hr` uses the default linear-interpolation quantile: with fewer than 21 intervals in a day, no observation sits
exactly at the 5th percentile and the result is interpolated between the two lowest readings. This is why the fixture
shows `63.7` rather than any value present in the input.

## Choices and limits

**`resting_hr` is a percentile, not a resting heart rate.** Established definitions of resting heart rate specify the
measurement conditions - typically sleep, or a seated rest protocol - and this field follows none of them. It is the 5th
percentile of whatever intervals the day happens to contain, which on a day with little sleep data is not a resting
measure at all. The percentile itself is a convention chosen here, with no source, which is why `--resting-quantile`
exists: a study with a definition of its own should set it rather than inherit this one.

Note that `analysis.activity.hr-activity-contrast` reports `inactive_hr`, computed differently again (the mean over
low-activity intervals). The two are **not** interchangeable.

**A percentile over three intervals is not a percentile over twenty-four**, and
`resting_hr` alone cannot tell you which you have. `observed_hr_intervals` is reported for that reason, rather than used
internally to reject sparse days: a minimum-completeness rule belongs to the study, not to the aggregation.

**`observed_hr_intervals` counts data, not wear.** A device worn but not recording does not count, and a gap the
cleaning step dropped does not either. It is a denominator for these features, not a wear-time measure.

**Dates come from naive timestamps.** The calendar date is read directly off the timestamp with no timezone handling, so
a study spanning zones or a daylight saving transition needs its timestamps normalised beforehand.

## Options

| Option               | Default  | Meaning                                               |
|----------------------|----------|-------------------------------------------------------|
| `--input`            | required | Heart rate on a fixed grid                            |
| `--output`           | required | Output CSV                                            |
| `--interval`         | `1h`     | Width of one input interval; checked against the data |
| `--resting-quantile` | `0.05`   | Heart-rate quantile reported as `resting_hr`, 0 to 1  |

Override these per use with `args:` on a `uses:` reference. A quantile outside 0 to 1, or a non-positive interval, stops
the step.

## References

No method paper: daily aggregation of a heart-rate series is a standard procedure, and the percentile is a convention
chosen for this step rather than a published definition. See Choices and limits. For context on why the naming of
`resting_hr` matters:

- [Measuring resting heart rate during daily life: behavioural context and methodological criteria](https://pmc.ncbi.nlm.nih.gov/articles/PMC12357026/)

## Implementations

| Language | Path                            |
|----------|---------------------------------|
| Python   | `impl/python/daily_hr_features.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.csv` within the declared tolerance.
`test_reproduces_the_reference_fixture` runs exactly that, so the claim is tested rather than asserted. Tests live
beside the implementation:

```bash
python -m pytest impl/python -q
```
