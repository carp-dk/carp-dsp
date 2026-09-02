#!/usr/bin/env python3
"""analysis.activity.hr-activity-contrast -
Contrast heart rate in active and inactive intervals.

For each participant-day, computes:
    - mean heart rate during active intervals
    - mean heart rate during inactive intervals
    - the difference between the two

Activity is determined from step counts.
This is a descriptive measure intended for exploratory analysis. It is not a validated physiological metric.

Inputs:
    --input     CSV with participant_id, timestamp, heart_rate_bpm, steps
Outputs:
    --output    CSV with participant_id, date, active_hr, inactive_hr,
                hr_activity_gap, active_intervals, inactive_intervals

Optional:
    --active-threshold  steps per interval at or above which an interval counts
                        as active, default 100 steps per interval
"""
from __future__ import annotations

import argparse

import pandas as pd

COLUMNS = [
    "participant_id", "date", "active_hr", "inactive_hr", "hr_activity_gap",
    "active_intervals", "inactive_intervals",
]

# Steps per interval, not steps/minute (cadence).
# The default is intentionally aligned with sensing.steps.daily-features so
# both analyses classify activity using the same threshold.
DEFAULT_ACTIVE_THRESHOLD = 100.0


def hr_activity_contrast(
    frame: pd.DataFrame,
    active_threshold: float = DEFAULT_ACTIVE_THRESHOLD,
) -> pd.DataFrame:
    """Contrast heart rate between active and inactive intervals, per participant-day.

    `hr_activity_gap` is defined as:

        mean(active heart rate) - mean(inactive heart rate)

    Intervals with a missing heart rate or step count take no part in either
    group.

    Args:
        frame: samples with participant_id, timestamp, heart_rate_bpm, steps.
        active_threshold: steps per interval at or above which an interval is active.

    Returns:
        One row per participant-day.
    """
    frame = frame.copy()
    frame["timestamp"] = pd.to_datetime(frame["timestamp"])
    frame["date"] = frame["timestamp"].dt.date
    frame["heart_rate_bpm"] = pd.to_numeric(frame["heart_rate_bpm"], errors="coerce")
    frame["steps"] = pd.to_numeric(frame["steps"], errors="coerce")

    # An interval can only be classified when both signals are present.
    usable = frame.dropna(subset=["heart_rate_bpm", "steps"])

    rows = []
    for (participant, date), group in usable.groupby(["participant_id", "date"], sort=True):
        is_active = group["steps"] >= active_threshold
        active = group.loc[is_active, "heart_rate_bpm"]
        inactive = group.loc[~is_active, "heart_rate_bpm"]

        active_hr = active.mean() if not active.empty else None
        inactive_hr = inactive.mean() if not inactive.empty else None
        gap = (
            active_hr - inactive_hr
            if active_hr is not None and inactive_hr is not None
            else None
        )

        rows.append({
            "participant_id": participant,
            "date": date,
            "active_hr": round(active_hr, 4) if active_hr is not None else None,
            "inactive_hr": round(inactive_hr, 4) if inactive_hr is not None else None,
            "hr_activity_gap": round(gap, 4) if gap is not None else None,
            "active_intervals": int(len(active)),
            "inactive_intervals": int(len(inactive)),
        })

    return pd.DataFrame(rows, columns=COLUMNS)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="hourly HR + steps CSV")
    parser.add_argument("--output", required=True, help="per participant-day contrast CSV")
    parser.add_argument(
        "--active-threshold", type=float, default=DEFAULT_ACTIVE_THRESHOLD,
        help="steps per interval, not a cadence",
    )
    args = parser.parse_args()

    result = hr_activity_contrast(
        pd.read_csv(args.input), active_threshold=args.active_threshold
    )
    result.to_csv(args.output, index=False)
    print(
        f"[analysis.activity.hr-activity-contrast] {len(result)} participant-day(s) "
        f"-> {args.output}"
    )


if __name__ == "__main__":
    main()
