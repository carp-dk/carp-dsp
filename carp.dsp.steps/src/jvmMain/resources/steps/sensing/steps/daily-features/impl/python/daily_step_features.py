#!/usr/bin/env python3
"""Library step: sensing.steps.daily-features.

Reduce a regularly sampled step-count series to one row per participant per day.

Input type:  clean step counts on a fixed grid
Output type: [participant_id, date, total_steps, active_intervals,
              observed_step_intervals]
"""
from __future__ import annotations

import argparse

import pandas as pd

DEFAULT_ACTIVE_THRESHOLD = 100.0

DEFAULT_INTERVAL = "1h"


def check_grid(frame: pd.DataFrame, interval: str) -> None:
    """Fail unless every gap between consecutive samples is a multiple of `interval`.

    `active_intervals` and `observed_step_intervals` are counts of intervals, so an
    interval has to stand for a fixed span of time before either means anything.
    Rather than assume the caller's grid matches `--interval`, check it: a
    mismatch is otherwise silent and produces numbers that look reasonable.

    Gaps may be any positive multiple of the interval, because a cleaned series
    is legitimately missing intervals. What is rejected is a spacing the interval
    does not divide.
    """
    step = pd.Timedelta(interval)
    if step <= pd.Timedelta(0):
        raise SystemExit(
            f"[sensing.steps.daily-features] --interval must be a positive duration, "
            f"got '{interval}'"
        )

    for participant, group in frame.groupby("participant_id"):
        gaps = group["timestamp"].sort_values().diff().dropna()
        bad = gaps[(gaps <= pd.Timedelta(0)) | (gaps % step != pd.Timedelta(0))]
        if not bad.empty:
            raise SystemExit(
                f"[sensing.steps.daily-features] participant {participant} is not on a "
                f"{interval} grid: found a spacing of {bad.iloc[0]}. Resample upstream, "
                f"or declare the grid the data is actually on with --interval."
            )


def daily_features(
        frame: pd.DataFrame,
        active_threshold: float = DEFAULT_ACTIVE_THRESHOLD,
) -> pd.DataFrame:
    """One row per participant-day of step-count features.

    `total_steps` and `active_intervals` are left empty for a day when no
    interval was observed, rather than summed to zero. Zero states that the
    participant did not move; empty states that nothing was recorded. Only
    `observed_step_intervals` is genuinely zero in that case, which is the point of
    reporting it.
    """
    frame = frame.copy()
    frame["date"] = frame["timestamp"].dt.date

    return frame.groupby(["participant_id", "date"]).agg(
        total_steps=("steps", lambda s: s.sum(min_count=1)),
        # Inclusive, matching hr-activity-contrast: an interval exactly on the
        # threshold counts as active in both steps or in neither.
        active_intervals=(
            "steps",
            lambda s: pd.NA if s.notna().sum() == 0 else int((s >= active_threshold).sum()),
        ),
        observed_step_intervals=("steps", lambda s: int(s.notna().sum())),
    ).reset_index()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, help="clean step counts on a fixed grid")
    ap.add_argument("--output", required=True, help="per participant-day CSV")
    ap.add_argument(
        "--interval", default=DEFAULT_INTERVAL,
        help="width of one input interval, e.g. 1h, 15min. Checked against the data",
    )
    ap.add_argument(
        "--active-threshold", type=float, default=DEFAULT_ACTIVE_THRESHOLD,
        help="steps in an interval at or above which it counts as active",
    )
    args = ap.parse_args()

    frame = pd.read_csv(args.input, parse_dates=["timestamp"])
    check_grid(frame, args.interval)

    result = daily_features(frame, active_threshold=args.active_threshold)
    result["total_steps"] = result["total_steps"].astype("Int64")
    result["active_intervals"] = result["active_intervals"].astype("Int64")

    result.to_csv(args.output, index=False)
    print(
        f"[sensing.steps.daily-features] {result['participant_id'].nunique()} participants, "
        f"{len(result)} participant-days on a {args.interval} grid -> {args.output}"
    )


if __name__ == "__main__":
    main()
