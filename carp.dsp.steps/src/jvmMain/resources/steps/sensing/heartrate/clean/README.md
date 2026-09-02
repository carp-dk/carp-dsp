# sensing.heartrate.clean

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Put a heart-rate series onto a regular time grid, dropping implausible readings
and bridging only short gaps.

```
raw-heart-rate (csv)  ->  clean-heart-rate (csv)
```

## Overview

Optical heart-rate exports arrive unevenly: timestamps repeat, readings drop out
when the sensor loses contact, and some values are outside anything a human
heart produces. This step removes implausible values, puts each participant on an
even grid, and fills gaps short enough to bridge - leaving longer outages as
visible holes rather than assumed data.

Reach for it as the first step after loading heart rate, before any daily
aggregation. Downstream steps assume evenly spaced intervals, and a mean taken
over an irregular series silently weights densely-sampled periods more heavily.

## Data it needs

| Column           | Type     | Meaning                                               |
|------------------|----------|-------------------------------------------------------|
| `participant_id` | string   | Participant identifier; each is gridded independently |
| `timestamp`      | datetime | When the reading was taken                            |
| `heart_rate_bpm` | float    | Heart rate, in beats per minute                       |

- **Granularity**: any sampling rate, regular or not. The output grid is set by
  `--interval` (hourly by default). Choose one near your source rate: a grid much
  finer than your data produces mostly empty buckets, and one much coarser
  averages away the variation you may have wanted.
- **Units**: beats per minute. Inter-beat intervals in milliseconds must be
  converted before this step.
- **CARP data type**: `dk.cachet.carp.heartrate` on both ports, and nothing else -
  which is what places this step in the `sensing` tier.
- **Missing data**: gaps of up to `--gap-limit` intervals are interpolated;
  anything longer is dropped, not filled.

## What you get

The same three columns, on a regular grid per participant, sorted by participant
then time.

| Field            | Meaning                                                             |
|------------------|---------------------------------------------------------------------|
| `participant_id` | Unchanged                                                           |
| `timestamp`      | On the requested grid                                               |
| `heart_rate_bpm` | Mean over the interval, or an interpolated value across a short gap |

From `reference/input.csv`, where p01 has a duplicate at 00:00, a reading of
`300` at 02:00, and no readings between 04:00 and 09:00:

```csv
participant_id,timestamp,heart_rate_bpm
p01,2026-01-01 00:00:00,62.0
p01,2026-01-01 01:00:00,65.0
p01,2026-01-01 02:00:00,67.0
p01,2026-01-01 03:00:00,69.0
p01,2026-01-01 04:00:00,71.0
p01,2026-01-01 05:00:00,70.4
p01,2026-01-01 06:00:00,69.8
p01,2026-01-01 07:00:00,69.2
p01,2026-01-01 08:00:00,
p01,2026-01-01 09:00:00,68.0
```

Three things to read from this. The `300` at 02:00 is masked, and 02:00 and 03:00
now hold values interpolated between the surviving neighbours at 01:00 and 04:00.
The duplicate at 00:00 resolved to `62.0`, the first of the pair, not the mean of
both. And **08:00 is empty** - the gap from 04:00 to 09:00 is four intervals, one
more than `--gap-limit`, so interpolation stops after three and the last interval
is reported with no value. A value you receive is a reading or a short bridge; an
empty cell is an outage, stated rather than left out.

## How it works

Applied in this order. Order matters: filtering before resampling keeps
implausible values out of the interval means, and interpolating after resampling
means the gap limit counts grid intervals rather than raw samples.

1. Parse timestamps and coerce `heart_rate_bpm` to numeric.
2. **Mask** readings outside `--min-bpm` to `--max-bpm`, **inclusive** at both
   ends. The row survives with an empty value: the reading is wrong, which is
   not the same as the interval not existing.
3. Per participant: sort by time, and reduce duplicate timestamps to their first
   **non-missing** reading.
4. Per participant: resample onto the `--interval` grid, taking the mean of the
   readings falling in each interval.
5. Linearly interpolate runs of at most `--gap-limit` consecutive empty
   intervals. `--gap-limit 0` disables this.
6. Emit intervals that are still empty, so an outage is visible in the output
   rather than absent from it. `--drop-gaps` removes them instead.

For an empty interval at position `k` in a run of at most `gap_limit` intervals,
bridged between the last value before the run at `a` and the first after it
at `b`:

```
grid_t = mean(heart_rate_bpm_i | t <= timestamp_i < t + interval)

x_k = x_a + (x_b - x_a) * (k - a) / (b - a)      for  b - a - 1 <= gap_limit
    = missing                                     otherwise
```

In the worked example above, `a` = 04:00 (71.0), `b` = 09:00 (68.0), so each
interval steps by `(68.0 - 71.0) / 5 = -0.6`: 70.4, 69.8, 69.2 - and then stops,
because 08:00 would be the fourth.

## Choices and limits

**The 30-220 bpm range is a working default, not a validated cut point.** It is
wide enough to keep almost any real reading, so it removes sensor errors rather
than physiological outliers. No source is cited for it - it is chosen here. If
your population makes it wrong in either direction (bradycardia, paediatric
cohorts, maximal exercise testing), set `--min-bpm` and `--max-bpm` yourself.

**Implausible values are masked.**
A bad reading means the sensor produced something unusable for that interval, not
that the interval did not happen.

**Duplicate timestamps keep the first non-missing reading**, not simply the
first. Two readings at one instant is usually a re-transmission rather than a
second measurement, so averaging would treat a duplicate as evidence; but taking
the first outright would let a masked duplicate hide the only real value.

**Interpolation invents values.** A bridged interval is an assumption that heart
rate moved linearly between two known readings. Over three hourly intervals -
the default - that assumption is doing real work, and the output does not mark
which values were bridged. If that matters for your analysis, set `--gap-limit 0`
and handle gaps yourself.

**An empty interval is not a zero and not an absence.** The output distinguishes
three things: a measured value, a bridged value, and an empty cell meaning the
sensor gave nothing usable. `--drop-gaps` collapses the third into an absent row,
which loses the difference between "no reading here" and "outside this
participant's recording period" - so it is off by default.

**It does not detect non-wear time.** A device removed but still recording can
report plausible-looking values. Non-wear detection is a separate problem
requiring its own step.

## Options

| Option        | Default  | Meaning                                                       |
|---------------|----------|---------------------------------------------------------------|
| `--input`     | required | Raw heart-rate CSV                                            |
| `--output`    | required | Output CSV                                                    |
| `--min-bpm`   | 30       | Lowest plausible heart rate, inclusive                        |
| `--max-bpm`   | 220      | Highest plausible heart rate, inclusive                       |
| `--interval`  | `1h`     | Output grid interval                                          |
| `--gap-limit` | 3        | Consecutive empty intervals to interpolate across; 0 disables |
| `--drop-gaps` | off      | Remove intervals still empty instead of emitting them         |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: range filtering, deduplication and linear interpolation are
standard bookkeeping rather than a published method, and the range bounds are a
convention chosen here. For context on handling free-living heart rate from
consumer wearables, including why cleaning decisions should be reported:

- [INTERLIVE-Network recommendations for free-living heart rate from consumer wearables](https://link.springer.com/article/10.1007/s40279-024-02159-1)

## Implementations

| Language | Path                   |
|----------|------------------------|
| Python   | `impl/python/clean_heart_rate.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.csv` within the declared tolerance. Tests live beside the
implementation:

```bash
python -m pytest impl/python -q
```
