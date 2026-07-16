#!/usr/bin/env python3
"""Library step: daily-features.

Aggregate the hourly HR + step table to one row per participant per day.

Input type:  hr-steps-csv
Output type: daily-features-csv
             [participant_id, date, mean_hr, resting_hr, peak_hr, total_steps, active_hours]

Reused by: wf-activity-summary, wf-anomaly-report, wf-minimal-summary.
"""
import argparse

import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="hr-steps-csv input path")
    ap.add_argument("--output", required=True, help="daily-features-csv output path")
    args = ap.parse_args()

    df = pd.read_csv(args.input, parse_dates=["timestamp"])
    df["date"] = df["timestamp"].dt.date

    feats = df.groupby(["participant_id", "date"]).agg(
        mean_hr=("heart_rate_bpm", "mean"),
        resting_hr=("heart_rate_bpm", lambda s: s.quantile(0.05)),
        peak_hr=("heart_rate_bpm", "max"),
        total_steps=("steps", "sum"),
        active_hours=("steps", lambda s: int((s > 100).sum())),
    ).reset_index()

    feats.to_csv(args.output, index=False)
    print(f"[daily-features] {feats['participant_id'].nunique()} participants, "
          f"{len(feats)} participant-days -> {args.output}")


if __name__ == "__main__":
    main()
