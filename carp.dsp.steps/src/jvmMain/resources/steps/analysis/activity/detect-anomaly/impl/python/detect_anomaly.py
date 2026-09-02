#!/usr/bin/env python3
"""Library step: analysis.activity.detect-anomaly.

Flag participant-days with unusually low activity or elevated resting heart rate,
against baselines computed per participant.

Input type:  daily features containing resting_hr and total_steps
Output type: [participant_id(str), date(YYYY-MM-DD), low_activity(Bool), elevated_resting_hr(Bool), flagged(Bool)]
"""
from __future__ import annotations

import argparse
import math

import pandas as pd

DEFAULT_MIN_DAYS = 14

DEFAULT_SD_MULTIPLIER = 2.0
DEFAULT_MEDIAN_FRACTION = 0.5

FLAGS = ("low_activity", "elevated_resting_hr", "flagged")


def min_days_floor(sd_multiplier: float) -> int:
    """Minimum number of days required to compute the resting HR test.

    For a dataset of n values, the largest possible z-score (using the population
    standard deviation) is (n - 1) / sqrt(n). This occurs when one value differs
    from n - 1 identical values.

    If this maximum possible z-score is smaller than the configured multiplier,
    the test can never succeed, regardless of the data. Therefore, the minimum
    sample size depends on the multiplier and must be computed rather than
    hardcoded.

    Examples:
        multiplier = 1.5 -> minimum n = 4
        multiplier = 2.0 -> minimum n = 6
        multiplier = 3.0 -> minimum n = 11
    """
    if sd_multiplier <= 0:
        raise SystemExit(
            f"[detect-anomaly] --hr-sd-multiplier must be positive, got {sd_multiplier}"
        )

    n = 2
    while (n - 1) / math.sqrt(n) <= sd_multiplier:
        n += 1
        if n > 10_000:
            raise SystemExit(
                f"[detect-anomaly] --hr-sd-multiplier of {sd_multiplier} needs more than "
                f"10,000 days before the test could ever fire. Lower it."
            )
    return n


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--input", required=True, help="daily features input path")
    ap.add_argument("--output", required=True, help="anomaly-csv output path")
    ap.add_argument(
        "--min-days", type=int, default=DEFAULT_MIN_DAYS,
        help="days a participant needs before any flag is decided",
    )
    ap.add_argument(
        "--hr-sd-multiplier", type=float, default=DEFAULT_SD_MULTIPLIER,
        help="standard deviations above the participant's mean resting HR to flag",
    )
    ap.add_argument(
        "--steps-median-fraction", type=float, default=DEFAULT_MEDIAN_FRACTION,
        help="fraction of the participant's median daily steps below which to flag",
    )
    args = ap.parse_args()

    if args.steps_median_fraction < 0:
        raise SystemExit(
            f"[detect-anomaly] --steps-median-fraction must not be negative, "
            f"got {args.steps_median_fraction}"
        )

    floor = min_days_floor(args.hr_sd_multiplier)
    min_days = max(args.min_days, floor)

    df = pd.read_csv(args.input)
    rows = []
    undecided = 0
    for pid, g in df.groupby("participant_id"):
        # Too few days: emit empty flags(ie cant be tested), not False.
        if len(g) < min_days:
            undecided += len(g)
            for _, r in g.iterrows():
                rows.append({"participant_id": pid, "date": r["date"],
                             **{flag: pd.NA for flag in FLAGS}})
            continue

        rhr_hi = g["resting_hr"].mean() + args.hr_sd_multiplier * g["resting_hr"].std(ddof=0)
        steps_lo = args.steps_median_fraction * g["total_steps"].median()
        for _, r in g.iterrows():
            low_act = bool(r["total_steps"] < steps_lo)
            hi_rhr = bool(r["resting_hr"] > rhr_hi)
            rows.append({
                "participant_id": pid, "date": r["date"],
                "low_activity": low_act, "elevated_resting_hr": hi_rhr,
                "flagged": low_act or hi_rhr,
            })

    out = pd.DataFrame(rows)
    for flag in FLAGS:
        out[flag] = out[flag].astype("boolean")
    out.to_csv(args.output, index=False)
    print(
        f"[detect-anomaly] flagged {int(out['flagged'].sum())}/{len(out)} days, "
        f"{undecided} undecided (fewer than {min_days} days, floor {floor} at a "
        f"multiplier of {args.hr_sd_multiplier}) -> {args.output}"
    )


if __name__ == "__main__":
    main()
