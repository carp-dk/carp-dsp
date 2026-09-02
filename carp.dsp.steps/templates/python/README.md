# {{ID}}

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

<!--
  One line: what this step produces. Written for a researcher deciding whether it
  belongs in their workflow, so lead with what it gives them, not with caveats.

  Keep the section order below - it follows the order a reader decides in: is this
  the right thing, can I feed it my data, what comes out, how does it work, what
  might make it wrong for me. The conformance gate checks these headings exist.
-->

Replace with a one-line summary of what the step produces.

```
<input-port> (csv)  ->  <output-port> (csv)
```

## Overview

Two to four sentences. What question does this step answer, and when would a
researcher reach for it? Say what it computes in plain language before any
detail. If a reader stops here, they should know whether to keep reading.

## Data it needs

What the step expects, precisely enough to check your own data against:

| Column | Type | Meaning |
| --- | --- | --- |
| `participant_id` | string | Participant identifier |
| `timestamp` | datetime | ... |

- **Granularity**: e.g. one row per participant per hour. Say what happens at
  other resolutions if it matters.
- **Units**: state them. A number without units is not reusable.
- **CARP data types**: which typed signals the ports declare, if any.
- **Missing data**: what the step does with gaps and masked values.

## What you get

Describe each output field, then show real output. A worked example answers
"is this what I need?" faster than any description.

| Field | Meaning |
| --- | --- |
| ... | ... |

```csv
participant_id,date,...
p01,2024-01-01,...
```

Say what a reader should take from those numbers.

## How it works

Numbered operations, in the order they are applied - order changes results, and
this section is the specification a second implementation is written from.

1. ...
2. ...
3. ...

**Give the equation for every computed field.** Prose describing an aggregation
is ambiguous in ways an equation is not - "standard deviation" does not say
whether the divisor is `n` or `n-1`, "resting heart rate" does not say which
percentile. Write the equation even when it looks obvious, and state the
denominator, the percentile, and whether a bound is inclusive:

```
active_hr   = mean(x_i | steps_i >= threshold)
inactive_hr = mean(x_i | steps_i <  threshold)

gap = active_hr - inactive_hr
```

Use plain notation a reader can transcribe into any language: `mean`, `median`,
`sum`, `sd(ddof=1)`, `quantile(x, 0.05)`, `|` for "given". Name the grouping the
equation is applied within, since the same formula over participant-days and
over a whole cohort are different steps.

## Choices and limits

Where a judgement was made, name it and say who made it. Anything a reader must
know before trusting the output belongs here, kept short and factual:

- **Thresholds and defaults**: is this value from the literature, or a convention
  chosen here? If chosen here, say so plainly.
- **Assumptions** about the data that could make the result wrong.
- **What it deliberately does not do**, especially where the name might suggest
  otherwise or another step reports a similarly-named field.

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `--input` | required | Input path |
| `--output` | required | Output path |

Override these per use with `args:` on a `uses:` reference.

## References

Cite the method where one exists. Where none does, say so explicitly - an empty
section reads as an oversight, a stated absence reads as a decision:

> No method paper: this step implements <standard procedure / a convention
> defined here>. See Choices and limits.

## Implementations

| Language | Path |
| --- | --- |
| Python | `impl/python/{{STEP_MODULE}}.py` |

Every implementation must reproduce `reference/expected.*` from
`reference/input.*` within the declared tolerance. Tests live beside the
implementation:

```bash
python -m pytest impl/python -q
```
