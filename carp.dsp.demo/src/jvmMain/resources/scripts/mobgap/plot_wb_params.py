#!/usr/bin/env python3
"""
Step 7: Walking Bout Plotting
=============================
CARP-DSP pipeline step - mobgap demo.

Creates a multi-panel PNG from walking-bout parameters:
- walking_speed_mps by wb_id
- cadence_spm by wb_id
- stride_length_m by wb_id
- stride_duration_s by wb_id
"""

import argparse
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="Plot walking-bout parameter trends")
    parser.add_argument("--wb-params", required=True, help="Input wb-params CSV")
    parser.add_argument("--output", required=True, help="Output PNG path")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        import matplotlib.pyplot as plt
        import seaborn as sns
    except ImportError:
        print("ERROR: seaborn/matplotlib not installed in environment")
        sys.exit(1)

    print(f"[PLOT-WB] Reading WB parameters: {args.wb_params}")
    wb = pd.read_csv(args.wb_params)
    if wb.empty:
        print("[PLOT-WB] ERROR: wb_params is empty")
        sys.exit(1)

    if "wb_id" not in wb.columns:
        wb = wb.reset_index(drop=True)
        wb.insert(0, "wb_id", wb.index.astype(int))

    metrics = [
        "walking_speed_mps",
        "cadence_spm",
        "stride_length_m",
        "stride_duration_s",
    ]
    available = [m for m in metrics if m in wb.columns]
    if not available:
        print("[PLOT-WB] ERROR: no expected metrics found in wb_params")
        sys.exit(1)

    sns.set_theme(style="whitegrid")
    fig, axes = plt.subplots(len(available), 1, figsize=(10, 2.8 * len(available)), sharex=True)
    if len(available) == 1:
        axes = [axes]

    for ax, metric in zip(axes, available):
        sns.lineplot(data=wb, x="wb_id", y=metric, marker="o", ax=ax)
        ax.set_title(metric)
        ax.set_xlabel("Walking bout ID")
        ax.set_ylabel(metric)

    fig.suptitle("Walking Bout Parameter Trends", fontsize=13)
    fig.tight_layout(rect=[0, 0, 1, 0.98])

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

    print(f"[PLOT-WB] Plot written: {out_path}")


if __name__ == "__main__":
    main()

