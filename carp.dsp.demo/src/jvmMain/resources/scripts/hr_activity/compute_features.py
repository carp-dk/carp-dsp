"""
Step 2 — Compute Features
Reads hourly HR + steps data and computes daily summary features:
  - mean_hr, resting_hr (5th percentile), peak_hr
  - total_steps, active_minutes (HR > 100 bpm)
"""
import argparse
import csv
from collections import defaultdict
from datetime import datetime


def percentile(values: list, p: float) -> float:
    sorted_vals = sorted(values)
    k = (len(sorted_vals) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    # Group by date
    days: dict[str, dict] = defaultdict(lambda: {"hr": [], "steps": 0, "active_minutes": 0})

    with open(args.input) as f:
        for row in csv.DictReader(f):
            date = datetime.fromisoformat(row["timestamp"]).date().isoformat()
            hr = float(row["heart_rate_bpm"])
            steps = int(row["steps"])
            days[date]["hr"].append(hr)
            days[date]["steps"] += steps
            if hr > 100:
                days[date]["active_minutes"] += 1  # 1 row = 1 hour; close enough for demo

    features = []
    for date in sorted(days):
        hrs = days[date]["hr"]
        features.append({
            "date": date,
            "mean_hr": round(sum(hrs) / len(hrs), 1),
            "resting_hr": round(percentile(hrs, 5), 1),
            "peak_hr": round(max(hrs), 1),
            "total_steps": days[date]["steps"],
            "active_hours": days[date]["active_minutes"],
        })

    fieldnames = ["date", "mean_hr", "resting_hr", "peak_hr", "total_steps", "active_hours"]
    with open(args.output, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(features)

    print(f"[compute_features] {len(features)} days written to {args.output}")
    for row in features:
        print(f"  {row['date']}: mean_hr={row['mean_hr']} resting={row['resting_hr']} steps={row['total_steps']}")


if __name__ == "__main__":
    main()
