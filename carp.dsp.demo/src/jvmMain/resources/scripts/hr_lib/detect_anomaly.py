#!/usr/bin/env python3
"""Library step: detect-anomaly.

Flag participant-days with unusual activity or heart rate, using per-participant baselines.

Input type:  daily-features-csv
Output type: anomaly-csv
             [participant_id, date, low_activity, elevated_resting_hr, flagged]

Reused by: wf-anomaly-report.
"""
import argparse

import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="daily-features-csv input path")
    ap.add_argument("--output", required=True, help="anomaly-csv output path")
    args = ap.parse_args()

    df = pd.read_csv(args.input)
    rows = []
    for pid, g in df.groupby("participant_id"):
        rhr_hi = g["resting_hr"].mean() + 2 * g["resting_hr"].std(ddof=0)
        steps_lo = 0.5 * g["total_steps"].median()
        for _, r in g.iterrows():
            low_act = bool(r["total_steps"] < steps_lo)
            hi_rhr = bool(r["resting_hr"] > rhr_hi)
            rows.append({
                "participant_id": pid, "date": r["date"],
                "low_activity": low_act, "elevated_resting_hr": hi_rhr,
                "flagged": low_act or hi_rhr,
            })

    out = pd.DataFrame(rows)
    out.to_csv(args.output, index=False)
    print(f"[detect-anomaly] flagged {int(out['flagged'].sum())}/{len(out)} days -> {args.output}")


if __name__ == "__main__":
    main()
