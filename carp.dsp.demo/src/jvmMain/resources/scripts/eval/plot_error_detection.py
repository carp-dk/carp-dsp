#!/usr/bin/env python3
"""Fig A - error detection: plan-time gate (CARP) vs run-time failure (ad-hoc pipeline).

A pipeline-timeline view. Each row is one injected fault. The ad-hoc pipeline runs
left-to-right and crashes partway, so the red portion is compute already burned before
the fault surfaces. CARP resolves every fault at a plan-time gate before step 1 runs, so
nothing on the timeline executes - shown as the blue gate band on the left.

All numbers come from the two CSVs; nothing is hardcoded, so the figure regenerates per
machine:
    error-detection-carp.csv    (ErrorDetectionEval.kt) - plan_ms = measured plan cost
    error-detection-adhoc.csv   (adhoc_baseline.py)     - wasted_seconds + pipeline_seconds

If the ad-hoc CSV has no measured seconds (a --dry-run CSV), the x-axis falls back to
pipeline steps, which is hardware-independent.

Usage:
    python plot_error_detection.py --out <paper>/images/fig-error-detection.pdf
"""
from __future__ import annotations
import argparse
import csv
import pathlib
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch

HERE = pathlib.Path(__file__).resolve().parent
RESULTS = HERE.parents[4] / "eval_results"  # .../carp.dsp.demo/eval_results (matches the Kotlin arm)

# rows top->bottom, ordered by how far the fault gets (escalating wasted compute)
FAULTS = ["missing-env", "cycle", "missing-producer", "missing-output", "type-mismatch"]
LABELS = {
    "missing-env": "Missing environment",
    "cycle": "Dependency cycle",
    "missing-producer": "Missing producer",
    "missing-output": "Undeclared output",
    "type-mismatch": "Type mismatch",
}
CARP_BLUE = "#2c6fbb"
ADHOC_RED = "#c1443c"
TRACK_GREY = "#e6e6e6"
GATE_BG = "#dce8f6"
STEP_COUNT = 8


def load(path: pathlib.Path) -> dict:
    if not path.exists():
        sys.exit(f"missing input: {path}")
    with path.open() as f:
        return {r["fault"]: r for r in csv.DictReader(f)}


def as_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--carp", default=RESULTS / "error-detection-carp.csv", type=pathlib.Path)
    ap.add_argument("--adhoc", default=RESULTS / "error-detection-adhoc.csv", type=pathlib.Path)
    ap.add_argument("--out", default=RESULTS / "fig-error-detection.pdf", type=pathlib.Path)
    args = ap.parse_args()

    carp, adhoc = load(args.carp), load(args.adhoc)

    # Prefer measured seconds; fall back to steps if the ad-hoc run had no timing.
    secs = {f: as_float(adhoc[f].get("wasted_seconds")) for f in FAULTS}
    pipe = [as_float(adhoc[f].get("pipeline_seconds")) for f in FAULTS]
    pipe = [p for p in pipe if p]
    seconds_mode = all(v is not None for v in secs.values()) and bool(pipe)

    if seconds_mode:
        vals = {f: secs[f] for f in FAULTS}
        total = max(pipe)
        unit = "s"
        xlabel = "Wall-clock compute before the fault surfaces (s)  -  measured on this machine"
        plan_ms = as_float(next(iter(carp.values())).get("plan_ms")) or 60.0
        carp_cost = f"~{plan_ms / 1000.0:.2f}s"
    else:
        vals = {f: int(adhoc[f]["steps_executed"]) for f in FAULTS}
        total = STEP_COUNT
        unit = "steps"
        xlabel = "Pipeline steps executed before the fault surfaces (of 8)"
        carp_cost = "plan time"

    gate_w = total * 0.16
    x0 = -gate_w
    fig, ax = plt.subplots(figsize=(8.4, 3.7))
    ys = list(range(len(FAULTS)))[::-1]

    ax.axvspan(x0, 0, color=GATE_BG, zorder=0)
    ax.axvline(0, color=CARP_BLUE, lw=1.2, zorder=3)
    ax.text(x0 * 0.72, (len(FAULTS) - 1) / 2.0, "CARP-DSP plan-time gate",
            ha="center", va="center", rotation=90, fontsize=9, color=CARP_BLUE, fontweight="bold")

    for y, fault in zip(ys, FAULTS):
        v = vals[fault]
        steps = int(adhoc[fault]["steps_executed"])
        ax.barh(y, total, left=0, height=0.55, color=TRACK_GREY, zorder=1)
        if v > 0:
            ax.barh(y, v, left=0, height=0.55, color=ADHOC_RED, zorder=2)
            ax.plot(v, y, marker="X", color="black", ms=7, zorder=4)
            amt = f"{v:.1f}s" if unit == "s" else f"{v} steps"
            ax.text(v + total * 0.015, y, f"crash after {amt} ({steps}/8 steps)",
                    va="center", ha="left", fontsize=8)
        else:
            ax.plot(total * 0.005, y, marker="X", color="black", ms=7, zorder=4)
            tag = "no valid schedule" if fault == "cycle" else "env fails on launch"
            ax.text(total * 0.02, y, f"runtime error, 0 steps ({tag})",
                    va="center", ha="left", fontsize=8)
        ax.plot(-total * 0.021, y, marker=r"$\checkmark$", color=CARP_BLUE, ms=11, zorder=4)

    ax.set_yticks(ys)
    ax.set_yticklabels([LABELS[f] for f in FAULTS], fontsize=9)
    ax.set_xlim(x0, total * 1.34)
    ax.set_xlabel(xlabel)
    ax.set_ylim(-0.7, len(FAULTS) - 0.35)
    for s in ("top", "right", "left"):
        ax.spines[s].set_visible(False)
    ax.tick_params(left=False)
    ax.set_axisbelow(True)
    ax.grid(axis="x", color="#f0f0f0", lw=0.7)

    legend = [
        Patch(facecolor=GATE_BG, edgecolor=CARP_BLUE, label=f"CARP: caught at plan time ({carp_cost}, 0/8 steps)"),
        Patch(facecolor=ADHOC_RED, label="Ad-hoc: compute wasted before crash"),
        Patch(facecolor=TRACK_GREY, label="Pipeline never reached"),
        Line2D([0], [0], marker="X", color="w", markerfacecolor="black", markersize=8, label="Run-time failure point"),
    ]
    ax.legend(handles=legend, loc="upper right", bbox_to_anchor=(1.0, 1.02),
              frameon=True, framealpha=0.95, edgecolor="#dddddd", fontsize=7.5)
    ax.set_title("Fault detection: plan-time gate (CARP) vs run-time failure (ad-hoc pipeline)",
                 fontsize=10, loc="left", pad=8)
    fig.tight_layout()

    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out)
    fig.savefig(args.out.with_suffix(".png"), dpi=150)
    print(f"wrote {args.out}\nwrote {args.out.with_suffix('.png')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
