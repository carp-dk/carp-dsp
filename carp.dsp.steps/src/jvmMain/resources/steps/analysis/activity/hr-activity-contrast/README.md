# analysis.activity.hr-activity-contrast

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Compare a participant's heart rate when they were moving against when they were not, one figure per day.

```
hr-steps (csv)  ->  hr-activity-contrast (csv)
```

## Overview

This step summarises daily heart rate separately during periods of activity and inactivity. Using step count to identify
active and inactive intervals, it reports the mean heart rate for each and the difference between them.

The output can help compare days within a participant while reducing the influence of changes in overall activity level.
It is intended as a descriptive summary rather than a validated physiological measure, and the results should be
interpreted in the context of the available data and study design.

## Data it needs

Heart rate and step counts for the same participants on a shared time grid, typically the output of a cleaning step for
each signal joined together.

| Column           | Type     | Meaning                                                 |
|------------------|----------|---------------------------------------------------------|
| `participant_id` | string   | Participant identifier                                  |
| `timestamp`      | datetime | Start of the interval                                   |
| `heart_rate_bpm` | float    | Mean heart rate over that interval, in beats per minute |
| `steps`          | integer  | Steps counted during that interval                      |

- **Granularity**: one row per participant per interval, hourly by default. The activity threshold is expressed per
  interval, so changing the interval changes what "active" means - see Choices and limits.
- **Units**: beats per minute; step counts per interval, not a cadence.
- **CARP data types**: `dk.cachet.carp.heartrate` and `dk.cachet.carp.stepcount`. Combining two collected signals is
  what places this step in the `analysis` tier.
- **Missing data**: an interval missing either signal takes part in neither group, so readings masked upstream are not
  counted as inactive.

## What you get

One row per participant-day.

| Field                | Meaning                                                           |
|----------------------|-------------------------------------------------------------------|
| `participant_id`     | Participant identifier                                            |
| `date`               | Calendar date the intervals fall on                               |
| `active_hr`          | Mean heart rate over intervals at or above the activity threshold |
| `inactive_hr`        | Mean heart rate over the remaining intervals                      |
| `hr_activity_gap`    | `active_hr - inactive_hr`                                         |
| `active_intervals`   | How many intervals `active_hr` was averaged over                  |
| `inactive_intervals` | How many intervals `inactive_hr` was averaged over                |

From `reference/input.csv`:

```csv
participant_id,date,active_hr,inactive_hr,hr_activity_gap,active_intervals,inactive_intervals
p01,2024-01-01,81.0059,59.3714,21.6345,17,7
p01,2024-01-02,73.6833,59.0167,14.6667,18,6
p02,2024-01-01,78.0056,58.6833,19.3222,18,6
```

Read the gap as "on this day, the participant's heart rate averaged about 22 bpm higher while moving than while still".
The interval counts matter as much as the means: a gap computed from two active intervals is far weaker evidence than
one from seventeen, and the counts are reported so that a reader can tell.

## How it works

1. Parse timestamps; coerce `heart_rate_bpm` and `steps` to numeric.
2. Drop intervals missing either signal - an interval can only be classified when both are present.
3. Assign each remaining interval a calendar date from its timestamp.
4. Within each participant-day, split intervals: **active** where
   `steps >= --active-threshold`, **inactive** otherwise.
5. Take the mean heart rate of each group, and count the intervals in each.
6. Subtract to get the gap. Where either group is empty, leave `active_hr`,
   `inactive_hr` or `hr_activity_gap` empty rather than reporting a number.

```
active_hr   = mean(heart_rate_bpm | steps >= threshold)
inactive_hr = mean(heart_rate_bpm | steps <  threshold)

hr_activity_gap = active_hr - inactive_hr
```

## Choices and limits

## Choices and limitations

**The activity threshold is a practical choice.**

Intervals are classified as active or inactive using a step-count threshold, which defaults to 100 steps per interval.
This value is intended to distinguish periods with meaningful movement from periods with little or no movement. The
appropriate threshold may vary depending on the interval length and study design, so it should be reviewed before use.

**`inactive_hr` should not be interpreted as resting heart rate.**

`inactive_hr` represents the average heart rate during intervals classified as inactive. These periods may include
sleep, sedentary behaviour, or other low-movement activities. Because it does not follow standard resting heart rate
measurement protocols, it should not be treated as a clinical resting heart rate measure.

Note that `sensing.heartrate.daily-features` provides a different metric called
`resting_hr`, derived using a separate method. The two measures capture different aspects of heart rate and should not
be used interchangeably.

**`hr_activity_gap` is a descriptive measure.**

`hr_activity_gap` represents the difference between mean heart rate during active and inactive periods. It is intended
to describe the contrast between those states within a day and should not be interpreted as a measure of cardiac
fitness, training load, or physiological stress.

**Days without both activity states cannot be evaluated.**

If a day contains no intervals classified as active, or no intervals classified as inactive, the contrast cannot be
calculated. In these cases, the output is left empty rather than reported as zero, since insufficient information is
available to estimate a meaningful difference.

## Options

| Option               | Default  | Meaning                                                        |
|----------------------|----------|----------------------------------------------------------------|
| `--input`            | required | Hourly heart rate and step-count CSV                           |
| `--output`           | required | Output CSV                                                     |
| `--active-threshold` | 100      | Steps **per interval** at or above which an interval is active |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: stratifying heart rate by concurrent activity is a descriptive contrast defined here, not a published,
validated measure. The references below are context for why the naming matters - particularly why `inactive_hr` avoids
the term resting heart rate - rather than validation of this metric:

- [INTERLIVE-Network recommendations for free-living heart rate from consumer wearables](https://link.springer.com/article/10.1007/s40279-024-02159-1)
- [Measuring resting heart rate during daily life: behavioural context and methodological criteria](https://pmc.ncbi.nlm.nih.gov/articles/PMC12357026/)

## Implementations

| Language | Path                                  |
|----------|---------------------------------------|
| Python   | `impl/python/hr_activity_contrast.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.csv` within the declared tolerance. Tests live beside the implementation:

```bash
python -m pytest impl/python -q
```
