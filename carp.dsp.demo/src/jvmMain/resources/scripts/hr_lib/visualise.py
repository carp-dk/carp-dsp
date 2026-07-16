#!/usr/bin/env python3
"""Library step: visualise.

Plot the daily features as a small multi-panel summary chart.

Input type:  daily-features-csv
Output type: plot-png

Reused by: wf-activity-summary, wf-anomaly-report.
"""
import argparse

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="daily-features-csv input path")
    ap.add_argument("--output", required=True, help="plot-png output path")
    args = ap.parse_args()

    df = pd.read_csv(args.input, parse_dates=["date"])
    fig, axes = plt.subplots(2, 2, figsize=(9, 6))
    by_day = df.groupby("date")
    axes[0, 0].plot(by_day["total_steps"].mean()); axes[0, 0].set_title("Mean total steps / day")
    axes[0, 1].plot(by_day["resting_hr"].mean()); axes[0, 1].set_title("Mean resting HR / day")
    axes[1, 0].hist(df["total_steps"], bins=20); axes[1, 0].set_title("Total steps distribution")
    axes[1, 1].scatter(df["total_steps"], df["mean_hr"], s=8, alpha=0.5)
    axes[1, 1].set_title("Steps vs mean HR"); axes[1, 1].set_xlabel("steps"); axes[1, 1].set_ylabel("HR")
    for ax in axes.flat:
        ax.tick_params(labelsize=8)
    fig.tight_layout()
    fig.savefig(args.output, dpi=120)
    print(f"[visualise] wrote {args.output}")


if __name__ == "__main__":
    main()
