#!/usr/bin/env python3
"""Library step: summarise.

Reduce the daily features to a single study-level summary.

Input type:  daily-features-csv
Output type: summary-json

Reused by: wf-activity-summary, wf-minimal-summary.
"""
import argparse
import json

import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="daily-features-csv input path")
    ap.add_argument("--output", required=True, help="summary-json output path")
    args = ap.parse_args()

    df = pd.read_csv(args.input)
    summary = {
        "n_participants": int(df["participant_id"].nunique()),
        "n_participant_days": int(len(df)),
        "mean_total_steps": round(float(df["total_steps"].mean()), 1),
        "median_total_steps": round(float(df["total_steps"].median()), 1),
        "mean_resting_hr": round(float(df["resting_hr"].mean()), 1),
        "mean_active_hours": round(float(df["active_hours"].mean()), 2),
    }
    with open(args.output, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"[summarise] {summary} -> {args.output}")


if __name__ == "__main__":
    main()
