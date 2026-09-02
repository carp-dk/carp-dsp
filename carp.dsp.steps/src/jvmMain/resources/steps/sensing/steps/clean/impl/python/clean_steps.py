#!/usr/bin/env python3
"""sensing.steps.clean - regularize a step-count series and mask invalid values.

Puts a step-count series onto a regular time grid and marks values that cannot be
true. A masked value is left empty.

Inputs:
    --input     CSV with participant_id, timestamp, steps
Outputs:
    --output    CSV with the same columns; invalid and missing values are empty

Optional:
    --interval    resampling interval, default 1h
    --max-steps   plausibility ceiling per interval; off unless given
"""
from __future__ import annotations

import argparse
from typing import Optional

import pandas as pd

COLUMNS = ["participant_id", "timestamp", "steps"]

DEFAULT_INTERVAL = "1h"

# A ceiling, if one is wanted, follows from cadence. Wearable step detection
# operates to roughly 150 steps/min, which is already vigorous, so a full hour at
# that rate is about 9000 steps. This is an outer bound on what a device could
# plausibly report for an hour, not a threshold for normal activity, and it is
# off by default: the right value depends on the interval, the device and the
# population, and a wrong ceiling silently deletes real data.
CADENCE_CEILING_PER_HOUR = 9000

DEFAULT_DROP_GAPS = False


def clean(
    frame: pd.DataFrame,
    interval: str = DEFAULT_INTERVAL,
    max_steps: Optional[int] = None,
    drop_gaps: bool = DEFAULT_DROP_GAPS,
) -> pd.DataFrame:
    """Regularize a step-count series and mask values that cannot be true.

    Duplicate timestamps keep the first non-missing reading. Negative counts are
    impossible and are masked. When `max_steps` is given, counts above it are
    masked too.

    Each participant is placed on a continuous grid at `interval`, with the
    readings falling in each interval summed. An interval with no reading stays
    empty rather than becoming zero.

    Args:
        frame: samples with participant_id, timestamp, steps.
        interval: pandas offset alias for the output grid.
        max_steps: plausibility ceiling per interval, or None to apply none.
        drop_gaps: remove empty intervals instead of emitting them. Loses the
            distinction between "no reading" and "outside this participant's
            recording period", so it is off by default.

    Returns:
        The series on a regular grid, with invalid and missing values empty.
    """
    frame = frame.copy()
    frame["timestamp"] = pd.to_datetime(frame["timestamp"])
    frame["steps"] = pd.to_numeric(frame["steps"], errors="coerce")

    frame.loc[frame["steps"] < 0, "steps"] = pd.NA
    if max_steps is not None:
        frame.loc[frame["steps"] > max_steps, "steps"] = pd.NA

    regularised = []
    for participant, group in frame.groupby("participant_id", sort=True):
        series = group.set_index("timestamp").sort_index()
        # First *non-missing* reading per timestamp, so a masked duplicate cannot
        # hide a good one recorded at the same instant. Deduplicating before the
        # resample also stops a re-transmission being counted twice in the sum.
        series = series[["steps"]].groupby(level=0).first()

        # Sum within each interval. `min_count=1` is what keeps an interval with
        # no reading empty: pandas otherwise sums an all-missing interval to 0,
        # which asserts "no steps" from an absence of evidence - the thing this
        # step exists to avoid.
        on_grid = series.resample(interval).sum(min_count=1)
        on_grid.index.name = "timestamp"
        if drop_gaps:
            on_grid = on_grid.dropna(subset=["steps"])
        on_grid["participant_id"] = participant
        regularised.append(on_grid.reset_index())

    if not regularised:
        return pd.DataFrame(columns=COLUMNS)

    result = pd.concat(regularised, ignore_index=True)[COLUMNS]
    result["steps"] = result["steps"].astype("Int64")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="raw step count CSV")
    parser.add_argument("--output", required=True, help="regularised step count CSV")
    parser.add_argument("--interval", default=DEFAULT_INTERVAL)
    parser.add_argument(
        "--max-steps", type=int, default=None,
        help=f"plausibility ceiling per interval; ~{CADENCE_CEILING_PER_HOUR} for an hour",
    )
    parser.add_argument(
        "--drop-gaps",
        action="store_true",
        help="remove empty intervals instead of emitting them",
    )
    args = parser.parse_args()

    result = clean(
        pd.read_csv(args.input),
        interval=args.interval,
        max_steps=args.max_steps,
        drop_gaps=args.drop_gaps,
    )
    masked = int(result["steps"].isna().sum())
    result.to_csv(args.output, index=False)
    print(
        f"[sensing.steps.clean] wrote {len(result)} rows, {masked} empty "
        f"(missing or masked) -> {args.output}"
    )


if __name__ == "__main__":
    main()
