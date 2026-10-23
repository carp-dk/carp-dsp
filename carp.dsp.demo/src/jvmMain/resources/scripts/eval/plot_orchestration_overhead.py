#!/usr/bin/env python3
"""Fig D - orchestration overhead per step of the Use Case 1 gait pipeline.

Reads eval_results/mobgap-timing.csv (MobgapTimedEval.kt) and draws one stacked bar
per step: script compute (the script's own EVAL_SCRIPT_SECONDS) and framework +
environment overhead (step total minus compute), with the overhead in ms above each
bar. Run 1 includes environment setup and the dataset download, so the figure uses
the median over the steady-state runs (run 2 onwards); with a single run it falls
back to that run and says so. Nothing is hardcoded; the figure regenerates per machine.

Usage:
    python plot_orchestration_overhead.py --out <paper>/images/fig-orchestration-overhead.pdf
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

COMPUTE_BLUE = "#4C72B0"
OVERHEAD_ORANGE = "#DD8452"

# Short axis labels for the mobgap step ids; any other step falls back to its name.
LABELS = {
    "import-data": "Import\nIMU",
    "gsd": "GSD",
    "icd": "ICD",
    "per-sec-params": "Per-sec\nparams",
    "wba": "WBA",
    "aggregate": "DMO\naggreg.",
    "plot-wb-params": "Plot WB\nparams",
    "plot-aggregated-dmos": "Plot\nDMOs",
}


def load(path: pathlib.Path) -> tuple[list[str], dict[str, str], dict[str, dict[str, list[float]]], list[int]]:
    """Returns step order, step names, per-step compute/overhead samples, and the runs used."""
    rows = [r for r in csv.DictReader(open(path, newline="")) if r["succeeded"] == "true"]
    runs = sorted({int(r["run"]) for r in rows})
    steady = [r for r in runs if r >= 2] or runs
    order: list[str] = []
    names: dict[str, str] = {}
    samples: dict[str, dict[str, list[float]]] = {}
    for r in rows:
        if int(r["run"]) not in steady or not r["compute_ms"]:
            continue
        step = r["step_id"]
        if step not in samples:
            order.append(step)
            names[step] = r["step_name"]
            samples[step] = {"compute": [], "overhead": []}
        samples[step]["compute"].append(float(r["compute_ms"]))
        samples[step]["overhead"].append(float(r["overhead_ms"]))
    return order, names, samples, steady


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=RESULTS / "mobgap-timing.csv", type=pathlib.Path)
    ap.add_argument("--out", default=RESULTS / "fig-orchestration-overhead.pdf", type=pathlib.Path)
    args = ap.parse_args()

    if not args.csv.exists():
        print(f"CSV not found: {args.csv} - run ./gradlew :carp.dsp.demo:evalMobgapTiming first", file=sys.stderr)
        return 1

    order, names, samples, runs = load(args.csv)
    if not order:
        print("No successful steps with script timings in the CSV - nothing to plot.", file=sys.stderr)
        return 1
    if runs == [1]:
        print("Only run 1 succeeded; it includes environment setup, so the figure is not steady state.",
              file=sys.stderr)

    compute_s = [statistics.median(samples[s]["compute"]) / 1000 for s in order]
    overhead_ms = [statistics.median(samples[s]["overhead"]) for s in order]
    overhead_s = [ms / 1000 for ms in overhead_ms]
    x = range(len(order))

    plt.rcParams.update({"font.family": "serif"})
    fig, ax = plt.subplots(figsize=(7.2, 3.5))
    ax.bar(x, compute_s, width=0.62, color=COMPUTE_BLUE, label="Script compute", zorder=2)
    ax.bar(x, overhead_s, width=0.62, bottom=compute_s, color=OVERHEAD_ORANGE,
           label="Framework + env overhead", zorder=2)
    for i, (c, o, ms) in enumerate(zip(compute_s, overhead_s, overhead_ms)):
        ax.text(i, c + o + 0.08, f"{ms:.0f}", ha="center", va="bottom", fontsize=8, color=OVERHEAD_ORANGE)

    ax.set_xticks(list(x))
    ax.set_xticklabels([LABELS.get(s, names[s]) for s in order], fontsize=8)
    ax.set_ylabel("Wall time per step (s)")
    ax.set_ylim(0, max(c + o for c, o in zip(compute_s, overhead_s)) * 1.12)
    ax.yaxis.grid(True, color="#dddddd", zorder=0)
    ax.spines[["top", "right"]].set_visible(False)
    ax.legend(frameon=False, fontsize=8, loc="upper right")

    fig.tight_layout()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, bbox_inches="tight")
    fig.savefig(args.out.with_suffix(".png"), dpi=150, bbox_inches="tight")
    print(f"Wrote {args.out} (median of run(s) {', '.join(map(str, runs))})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
