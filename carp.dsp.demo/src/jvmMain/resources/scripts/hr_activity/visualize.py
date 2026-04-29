"""
Step 3 — Visualize
Reads daily features and produces a 2x2 summary PNG:
  - Mean vs resting HR over the week
  - Peak HR
  - Daily step count with 8 000-step target line
  - Active hours per day
"""
import argparse
import csv
import subprocess
import sys
from datetime import datetime

try:
    import matplotlib
except ImportError:
    print("[visualize] matplotlib not found — installing...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "matplotlib", "--quiet"])
    import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    rows = []
    with open(args.input) as f:
        for row in csv.DictReader(f):
            rows.append(row)

    if not rows:
        raise ValueError("No feature rows found in input.")

    dates = [datetime.fromisoformat(r["date"]) for r in rows]
    mean_hr = [float(r["mean_hr"]) for r in rows]
    resting_hr = [float(r["resting_hr"]) for r in rows]
    peak_hr = [float(r["peak_hr"]) for r in rows]
    steps = [int(r["total_steps"]) for r in rows]
    active = [int(r["active_hours"]) for r in rows]

    fig, axes = plt.subplots(2, 2, figsize=(10, 7))
    fig.suptitle("7-Day Heart Activity Summary", fontsize=14, fontweight="bold")

    fmt = mdates.DateFormatter("%a")

    # Heart rate trends
    ax = axes[0, 0]
    ax.plot(dates, mean_hr, "o-", color="#E74C3C", label="Mean HR")
    ax.plot(dates, resting_hr, "s--", color="#3498DB", label="Resting HR")
    ax.set_title("Heart Rate (bpm)")
    ax.xaxis.set_major_formatter(fmt)
    ax.legend(fontsize=8)
    ax.set_ylim(40, 110)
    ax.grid(True, alpha=0.3)

    # Peak HR
    ax = axes[0, 1]
    ax.bar(dates, peak_hr, color="#E74C3C", alpha=0.7, width=0.6)
    ax.set_title("Peak HR (bpm)")
    ax.xaxis.set_major_formatter(fmt)
    ax.set_ylim(0, 200)
    ax.grid(True, alpha=0.3, axis="y")

    # Daily steps
    ax = axes[1, 0]
    colors = ["#2ECC71" if s >= 8000 else "#E67E22" for s in steps]
    ax.bar(dates, steps, color=colors, alpha=0.8, width=0.6)
    ax.axhline(8000, color="grey", linestyle="--", linewidth=1, label="8 000 target")
    ax.set_title("Daily Steps")
    ax.xaxis.set_major_formatter(fmt)
    ax.legend(fontsize=8)
    ax.grid(True, alpha=0.3, axis="y")

    # Active hours
    ax = axes[1, 1]
    ax.bar(dates, active, color="#9B59B6", alpha=0.8, width=0.6)
    ax.set_title("Active Hours (HR > 100 bpm)")
    ax.xaxis.set_major_formatter(fmt)
    ax.set_ylim(0, max(active) + 2 if active else 5)
    ax.grid(True, alpha=0.3, axis="y")

    plt.tight_layout()
    plt.savefig(args.output, dpi=120, bbox_inches="tight")
    plt.close()
    print(f"[visualize] plot saved to {args.output}")


if __name__ == "__main__":
    main()
