# analysis.io.generate-hr-steps

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Synthesize an hourly heart-rate and step-count table, deterministically from a seed.

```
(no input)  ->  hr-steps (csv)
```

## Overview

This step generates synthetic hourly heart-rate and step-count data from a user-specified seed. The output follows the
same schema as data produced by the standard loaders, allowing it to be used as a substitute input when developing,
testing, or demonstrating workflows.

The generated data is designed to be used for testing and development purposes, not to produce results. The output has a
plausible daily shape and nothing else: it is not a model of physiology, and no finding can rest on it.

## Data it needs

Nothing. The step has no input port; everything is an argument.

- **Granularity**: output is hourly, fixed. Rows are emitted for every hour of every day with no gaps.
- **Units**: beats per minute; step counts per hour.
- **CARP data types**: heart rate and step count on the output port, so the generated table can be consumed by typed
  downstream steps. Two collected signals, which places this step in the `analysis` tier.

  That reads oddly for a generator, and it is worth saying why it is right. The tier is not a claim about what a step
  computes - this one derives nothing, it fabricates. It is a claim about how widely the step can be reused, and a
  generator of heart rate and step counts is usable only where both are wanted, exactly like a step that analyses them.
  The step previously declared no types at all and sat in `core` on that basis, which said it was reusable anywhere. It
  never was.
- **Missing data**: none is generated. Every hour has both values, which makes this poor input for testing how a
  pipeline handles gaps.

## What you get

One row per participant per hour, four columns, participants in order and hours ascending.

| Field            | Meaning                                                     |
|------------------|-------------------------------------------------------------|
| `participant_id` | `p01`, `p02`, ... in order                                  |
| `timestamp`      | Hourly from `2024-01-01 00:00:00`, as `YYYY-MM-DD HH:MM:SS` |
| `heart_rate_bpm` | Simulated heart rate, one decimal place                     |
| `steps`          | Simulated step count for that hour, an integer              |

From `reference/expected.csv`, two participants over one day at seed 42:

```csv
participant_id,timestamp,heart_rate_bpm,steps
p01,2024-01-01 00:00:00,57.4,0
p01,2024-01-01 01:00:00,57.3,0
p01,2024-01-01 02:00:00,57.6,0
p01,2024-01-01 03:00:00,60.8,0
```

Read the zeros as literal zeros, not missing data: before 06:00 the model emits no steps at all. The same command with
the same seed produces these exact bytes on any machine, which is what lets this step back a reference fixture and a
repeatable test.

## How it works

1. For each participant `p` in `0 ... participants-1`, seed a generator with
   `seed + p` and label them `p{p+1:02d}`.
2. For each day `d` and hour `h`, draw a heart rate and a step count from the band the hour falls in.
3. Round the heart rate to one decimal place and emit the row with timestamp
   `2024-01-01 00:00 + d days + h hours`.

The model is a table of time-of-day bands. `N(mu, sigma)` is a normal draw,
`U{a..b}` a uniform integer draw inclusive of both ends:

```
hour        heart_rate_bpm    steps
--------------------------------------------
00 - 05     N(58,  4)         0
06 - 08     N(72,  8)         U{500..2000}
09 - 11     N(78, 10)         U{800..2500}
12 - 13     N(80, 12)         U{600..1500}
14 - 17     N(82, 10)         U{800..2000}
18 - 20     N(95, 15)         U{1000..4000}    when day is even
18 - 20     N(75,  8)         U{300..800}      when day is odd
21 - 23     N(65,  5)         U{0..200}

heart_rate_bpm = round(draw, 1)
```

Days are numbered from zero, so day 0 - the first - takes the even branch. Each participant draws from their own
generator in a fixed order, so **a participant's series does not change when others are added**: generating three
participants gives byte-identical rows for `p01` and `p02` as generating two.

## Choices and limits

**It is not a physiological model.** The bands were chosen to look reasonable, not to represent real physiology. Heart
rate and steps within an hour are drawn independently, so the correlation any real wearable shows is absent - which
makes this data actively misleading for testing an analysis that looks for that relationship.

**No result computed from this data means anything.** It exists to prove a pipeline runs, not to say anything about
people. Any figure produced from it should be labelled synthetic wherever it appears.

**Range of draws is not constrained.** Nothing bounds a heart rate to a plausible range. The distributions make an
implausible value very unlikely rather than impossible, so a downstream cleaning step should not be assumed unnecessary.

**There are no gaps, duplicates or bad values.** Every hour is present and plausible. A pipeline tested only against
this input has not been tested against the failure modes real exports have - which is most of what the cleaning steps
exist for.

**The evening spike alternates by day number, not by anything meaningful.** It gives the series some between-day
variation so aggregation and anomaly steps have something to find.

**The start date is fixed** at 2024-01-01 and is not an option.

## Options

| Option           | Default  | Meaning                                            |
|------------------|----------|----------------------------------------------------|
| `--output`       | required | Output CSV path                                    |
| `--participants` | 2        | Number of participants                             |
| `--days`         | 7        | Days per participant                               |
| `--seed`         | 42       | Seed; the same seed always produces the same table |

Override these per use with `args:` on a `uses:` reference.

## References

No method paper: the diurnal bands are a convention chosen for this step to give downstream steps something with daily
structure to work on. They are not drawn from any dataset or published model. See Choices and limits.

## Implementations

| Language | Path                               |
|----------|------------------------------------|
| Python   | `impl/python/generate_hr_steps.py` |

Every implementation must reproduce `reference/expected.csv` - two participants over one day at seed 42 - within the
declared tolerance. There is no input fixture: the step takes no input. Reproducing it requires matching the generator
and the draw order exactly, not only the distributions. Tests live beside the implementation:

```bash
python -m pytest impl/python -q
```
