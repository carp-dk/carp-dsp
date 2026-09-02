#!/usr/bin/env python3
"""Library step: sensing.heartrate.daily-features.

Aggregate a sampled heart-rate series to one row per participant per day.

Input type:  clean heart rate on a fixed grid
Output type: [participant_id, date, mean_hr, resting_hr, peak_hr,
              observed_hr_intervals]
"""
from __future__ import annotations

import argparse

import pandas as pd

DEFAULT_RESTING_QUANTILE = 0.05
DEFAULT_INTERVAL = "1h"


def check_grid(frame: pd.DataFrame, interval: str) -> None:
    """Fail unless every gap between consecutive samples is a multiple of `interval`.

    `observed_hr_intervals` is a count of intervals, so an interval has to stand for
    a fixed span of time before the count means anything. Rather than assume the
    caller's grid matches `--interval`, check it.

    Gaps may be any positive multiple of the interval, because a cleaned series
    is legitimately missing intervals. What is rejected is a spacing the interval
    does not divide.
    """
    step = pd.Timedelta(interval)
    if step <= pd.Timedelta(0):
        raise SystemExit(
            f"[sensing.heartrate.daily-features] --interval must be a positive duration, "
            f"got '{interval}'"
        )

    for participant, group in frame.groupby("participant_id"):
        gaps = group["timestamp"].sort_values().diff().dropna()
        bad = gaps[(gaps <= pd.Timedelta(0)) | (gaps % step != pd.Timedelta(0))]
        if not bad.empty:
            raise SystemExit(
                f"[sensing.heartrate.daily-features] participant {participant} is not on a "
                f"{interval} grid: found a spacing of {bad.iloc[0]}. Resample upstream, "
                f"or declare the grid the data is actually on with --interval."
            )


def daily_features(
        frame: pd.DataFrame,
        resting_quantile: float = DEFAULT_RESTING_QUANTILE,
) -> pd.DataFrame:
    """One row per participant-day of heart-rate features.

    A day with no observed heart rate reports empty features and an
    `observed_hr_intervals` of zero, which is the only field that can honestly be
    zero on such a day.
    """
    frame = frame.copy()
    frame["date"] = frame["timestamp"].dt.date

    return frame.groupby(["participant_id", "date"]).agg(
        mean_hr=("heart_rate_bpm", "mean"),
        resting_hr=("heart_rate_bpm", lambda s: s.quantile(resting_quantile)),
        peak_hr=("heart_rate_bpm", "max"),
        observed_hr_intervals=("heart_rate_bpm", lambda s: int(s.notna().sum())),
    ).reset_index()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, help="clean heart rate on a fixed grid")
    ap.add_argument("--output", required=True, help="per participant-day CSV")
    ap.add_argument(
        "--interval", default=DEFAULT_INTERVAL,
        help="width of one input interval, e.g. 1h, 15min. Checked against the data",
    )
    ap.add_argument(
        "--resting-quantile", type=float, default=DEFAULT_RESTING_QUANTILE,
        help="heart-rate quantile reported as resting_hr, between 0 and 1",
    )
    args = ap.parse_args()

    if not 0.0 <= args.resting_quantile <= 1.0:
        raise SystemExit(
            f"[sensing.heartrate.daily-features] --resting-quantile must be between "
            f"0 and 1, got {args.resting_quantile}"
        )

    frame = pd.read_csv(args.input, parse_dates=["timestamp"])
    check_grid(frame, args.interval)

    result = daily_features(frame, resting_quantile=args.resting_quantile)
    result.to_csv(args.output, index=False)
    print(
        f"[sensing.heartrate.daily-features] {result['participant_id'].nunique()} participants, "
        f"{len(result)} participant-days on a {args.interval} grid -> {args.output}"
    )


if __name__ == "__main__":
    main()
