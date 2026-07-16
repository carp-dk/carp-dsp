#!/usr/bin/env python3
"""Library step: clean-resample.

Quality-control the hourly HR + step table: drop physiologically impossible heart rates,
clip outliers, and resample each participant onto a continuous hourly grid.

Input type:  hr-steps-csv
Output type: hr-steps-csv  (same schema, cleaned)

Reused by: wf-activity-summary, wf-anomaly-report.
"""
import argparse

import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="hr-steps-csv input path")
    ap.add_argument("--output", required=True, help="hr-steps-csv output path")
    args = ap.parse_args()

    df = pd.read_csv(args.input, parse_dates=["timestamp"])
    df = df[(df["heart_rate_bpm"] >= 30) & (df["heart_rate_bpm"] <= 220)]
    df["steps"] = df["steps"].clip(lower=0)

    out = []
    for pid, g in df.groupby("participant_id"):
        g = g.set_index("timestamp").sort_index()
        g = g[~g.index.duplicated(keep="first")]
        g = g.resample("1h").agg({"heart_rate_bpm": "mean", "steps": "sum"})
        g["heart_rate_bpm"] = g["heart_rate_bpm"].interpolate(limit=3)
        g["steps"] = g["steps"].fillna(0)
        g = g.dropna(subset=["heart_rate_bpm"])
        g["participant_id"] = pid
        out.append(g.reset_index())

    res = pd.concat(out, ignore_index=True)[
        ["participant_id", "timestamp", "heart_rate_bpm", "steps"]]
    res.to_csv(args.output, index=False)
    print(f"[clean-resample] {len(df)} -> {len(res)} rows -> {args.output}")


if __name__ == "__main__":
    main()
