# analysis.activity.detect-anomaly

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Flag participant-days that stand out against that participant's own baseline.

```
daily-features (csv)  ->  anomaly (csv)
```

## Overview

This step identifies participant-days that differ from an individual's typical pattern of activity and resting heart
rate. Rather than applying the same threshold to all participants, it establishes a baseline for each participant using
their available data and flags days with unusually low activity or unusually high resting heart rate.

The output is intended as a screening aid to help identify days that may warrant further investigation. A flag indicates
that a day differs from the participant's usual pattern, not that a clinically meaningful event has occurred.
Interpretation should consider the amount and quality of data available for each participant.

## Data it needs

Per participant-day features carrying both signals, typically
`sensing.steps.daily-features` and `sensing.heartrate.daily-features` joined on
`participant_id` and `date`.

| Column           | Type    | Meaning                                               |
|------------------|---------|-------------------------------------------------------|
| `participant_id` | string  | Participant identifier; baselines are per participant |
| `date`           | date    | Calendar date                                         |
| `resting_hr`     | float   | The day's low heart-rate summary                      |
| `total_steps`    | integer | Steps that day                                        |

Other columns are ignored and are **not** carried through.

- **Granularity**: one row per participant per day.
- **Units**: beats per minute; step counts per day.
- **CARP data types**: heart rate on `resting_hr` and step count on
  `total_steps`. Two collected signals, which places this step in the `analysis`
  tier. It sat in `core` until those declarations were added - the tier rule is a count of declared types, so a step
  that declares nothing lands in `core` whether it is generic, and this one never was.
- **Missing data**: days absent from the input are not flagged and do not enter the baseline. Their absence is not
  itself reported.

## What you get

One row per participant-day, in the same order as the input.

| Field                 | Meaning                                                                                      |
|-----------------------|----------------------------------------------------------------------------------------------|
| `participant_id`      | Participant identifier                                                                       |
| `date`                | Calendar date                                                                                |
| `low_activity`        | `True` where the day's steps fell below the participant's floor; empty if undecided          |
| `elevated_resting_hr` | `True` where the day's `resting_hr` rose above the participant's ceiling; empty if undecided |
| `flagged`             | `True` where either flag is set; empty if undecided                                          |

From `reference/input.csv`, a fortnight for each of two participants:

```csv
participant_id,date,low_activity,elevated_resting_hr,flagged
p01,2016-04-20,False,False,False
p01,2016-04-21,True,False,True
p01,2016-04-22,False,False,False
p01,2016-04-23,False,True,True
```

Two days of p01's fortnight are flagged, each by a different rule: 21 April on steps, 23 April on resting heart rate.
p02's fortnight is unremarkable throughout and every one of its flags is `False` - a step that only ever flagged would
be just as useless as one that never did.

The fixture used to hold two days per participant against a fourteen-day minimum, so every published value was empty.
That is the right answer for two days, but it made a poor reference: a second implementation could reproduce it exactly
while getting the decision rule completely wrong.

Read a decided flag as "worth looking at", not as an event: **flags are relative to the participant, and always to the
very days being tested.**

## How it works

1. Read the features; group by `participant_id`.
2. Compute two baselines per participant, from that participant's days.
3. Compare each day against its participant's baselines and set the flags.

Over the days `d` belonging to participant `p`:

```
ceiling_p = mean(resting_hr_d) + k * sd(resting_hr_d, ddof=0)      k = --hr-sd-multiplier
floor_p   = f * median(total_steps_d)                              f = --steps-median-fraction

low_activity_d        = total_steps_d < floor_p            strictly less than
elevated_resting_hr_d = resting_hr_d  > ceiling_p          strictly greater than

flagged_d = low_activity_d OR elevated_resting_hr_d
```

Note `ddof = 0`: the standard deviation divides by `n`, not `n - 1`, so it describes the days present rather than
estimating a wider population.

## Choices and limits

The `flagged` status (`True/False/Empty`) is determined by an OR condition combining two primary statistical tests:

* **Low Activity:** Steps fall below the calculated floor ($\text{total\_steps} < \text{floor\_p}$).
* **Elevated HR:** Resting Heart Rate exceeds the calculated ceiling ($\text{resting\_hr} > \text{ceiling\_p}$).

*(Note: An empty flag means the comparison was undefined; `False` means tested and within bounds.)*

**Dynamic Baseline Dependency:**
The analysis uses a **moving window** approach, calculating baseline statistics (mean, median, SD) from the same data
being tested. Flags are therefore *relative*: adding new data points will statistically change which historical days are
flagged. For stable trend analysis, always compute baselines over a fixed reference period outside of this process.

**Minimum Data Requirement (`--min-days`):**
The system enforces a mathematically derived minimum number of days for statistical validity. If fewer days are present
than required, the result will be **empty flags**. Users must distinguish this state from `False`, as an empty flag
signifies *insufficient data* to test against.

**Data Source & Scope:**

* **`resting_hr`**: This feature is a statistical proxy derived from aggregate percentiles (e.g., 5th percentile) of
  upstream data, **not** a standard clinical resting heart rate measurement.
* **Flag Status**: A flag indicates a **statistical outlier**, not a diagnosis or definitive physiological event. The
  step cannot differentiate between patterns caused by illness, non-wear, or charging.

**System Defaults:**
All thresholds (e.g., 2 SDs) are established methodological defaults and do not carry inherent clinical validity. They
function as adjustable parameters within the analysis framework.

## Options

| Option                    | Default  | Meaning                                                                          |
|---------------------------|----------|----------------------------------------------------------------------------------|
| `--input`                 | required | Daily features CSV                                                               |
| `--output`                | required | Output CSV                                                                       |
| `--min-days`              | 14       | Days a participant needs before any flag is decided; raised to the floor for `k` |
| `--hr-sd-multiplier`      | 2.0      | `k` - standard deviations above the participant's mean resting HR                |
| `--steps-median-fraction` | 0.5      | `f` - fraction of the participant's median daily steps                           |

Override these per use with `args:` on a `uses:` reference. A non-positive multiplier, or a negative fraction, stops the
step.

## References

No method paper: per-participant baseline thresholding is a convention defined here.

## Implementations

| Language | Path                            |
|----------|---------------------------------|
| Python   | `impl/python/detect_anomaly.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.csv` within the declared tolerance. Tests live beside the implementation:

```bash
python -m pytest impl/python -q
```
