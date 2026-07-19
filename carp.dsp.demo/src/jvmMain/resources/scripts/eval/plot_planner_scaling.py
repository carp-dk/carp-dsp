#!/usr/bin/env python3
"""Fig E - planner scaling: plan-time gate cost vs workflow size.

Reads eval_results/planner-scaling.csv (PlannerScalingEval.kt) and plots the median
per-phase and total time of the decode -> import -> plan -> validate path against
synthetic linear-chain workflow size. Error band is the interquartile range of the
total. Nothing is hardcoded; the figure regenerates per machine.

Usage:
    python plot_planner_scaling.py --out <paper>/images/fig-planner-scaling.pdf
"""
from __future__ import annotations
import argparse
import csv
import pathlib
import statistics
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = pathlib.Path(__file__).resolve().parent
RESULTS = HERE.parents[4] / "eval_results"  # matches the Kotlin arm

PHASES = ["decode_ms", "import_ms", "plan_ms", "validate_ms"]
PHASE_LABELS = {
    "decode_ms": "Decode (YAML)",
    "import_ms": "Import",
    "plan_ms": "Plan",
    "validate_ms": "Validate",
}


def load(path: pathlib.Path) -> dict[int, dict[str, list[float]]]:
    by_size: dict[int, dict[str, list[float]]] = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            size = int(row["size"])
            d = by_size.setdefault(size, {p: [] for p in PHASES + ["total_ms"]})
            for p in PHASES + ["total_ms"]:
                d[p].append(float(row[p]))
    return by_size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=RESULTS / "planner-scaling.csv", type=pathlib.Path)
    ap.add_argument("--out", default=RESULTS / "fig-planner-scaling.pdf", type=pathlib.Path)
    args = ap.parse_args()

    if not args.csv.exists():
        print(f"CSV not found: {args.csv} - run ./gradlew :carp.dsp.demo:evalPlannerScaling first", file=sys.stderr)
        return 1

    by_size = load(args.csv)
    sizes = sorted(by_size)

    fig, ax = plt.subplots(figsize=(6.0, 3.2))

    for phase in PHASES:
        med = [statistics.median(by_size[s][phase]) for s in sizes]
        ax.plot(sizes, med, marker="o", markersize=3.5, linewidth=1.2,
                label=PHASE_LABELS[phase], alpha=0.85)

    total_med = [statistics.median(by_size[s]["total_ms"]) for s in sizes]
    q1 = [statistics.quantiles(by_size[s]["total_ms"], n=4)[0] for s in sizes]
    q3 = [statistics.quantiles(by_size[s]["total_ms"], n=4)[2] for s in sizes]
    ax.plot(sizes, total_med, marker="s", markersize=4.5, linewidth=2.0,
            color="black", label="Total")
    ax.fill_between(sizes, q1, q3, color="black", alpha=0.12, linewidth=0)

    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(sizes)
    ax.set_xticklabels([str(s) for s in sizes])
    ax.minorticks_off()
    ax.set_xlabel("Workflow size (steps, linear chain)")
    ax.set_ylabel("Median time (ms)")
    ax.grid(True, which="major", alpha=0.3, linewidth=0.5)
    # Legend above the axes so it never overlaps the (monotonically rising) lines;
    # the headline numbers belong in the paper caption, not a title.
    ax.legend(
        fontsize=8, frameon=False, ncol=3,
        loc="lower left", bbox_to_anchor=(0, 1.02, 1, 0.2), mode="expand",
        borderaxespad=0,
    )
    fig.tight_layout()

    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, bbox_inches="tight")
    print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
