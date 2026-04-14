#!/usr/bin/env python3
"""
Step 8: Aggregated DMO Plotting
===============================
CARP-DSP pipeline step - mobgap demo.

Creates a compact bar-chart PNG for key aggregated DMOs.
"""

import argparse
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="Plot aggregated DMO summary")
    parser.add_argument("--aggregated-dmos", required=True, help="Input aggregated DMOs CSV")
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

    print(f"[PLOT-DMO] Reading aggregated DMOs: {args.aggregated_dmos}")
    agg = pd.read_csv(args.aggregated_dmos)
    if agg.empty:
        print("[PLOT-DMO] ERROR: aggregated DMOs input is empty")
        sys.exit(1)

    row = agg.iloc[0]
    preferred = [
        "walking_speed_mps",
        "cadence_spm",
        "stride_length_m",
        "stride_duration_s",
    ]

    metric_values = []
    for metric in preferred:
        if metric in row.index and pd.notna(row[metric]):
            metric_values.append((metric, float(row[metric])))

    if not metric_values:
        fallback = [
            (str(col), float(row[col]))
            for col in agg.columns
            if pd.api.types.is_numeric_dtype(agg[col]) and pd.notna(row[col])
        ]
        metric_values = fallback[:6]

    if not metric_values:
        print("[PLOT-DMO] ERROR: no numeric metrics available to plot")
        sys.exit(1)

    plot_df = pd.DataFrame(metric_values, columns=["metric", "value"])

    sns.set_theme(style="whitegrid")
    fig, ax = plt.subplots(figsize=(10, 4))
    sns.barplot(data=plot_df, x="metric", y="value", ax=ax, palette="deep")
    ax.set_title("Aggregated Digital Mobility Outcomes")
    ax.set_xlabel("")
    ax.set_ylabel("Value")
    ax.tick_params(axis="x", rotation=25)
    fig.tight_layout()

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

    print(f"[PLOT-DMO] Plot written: {out_path}")


if __name__ == "__main__":
    main()

