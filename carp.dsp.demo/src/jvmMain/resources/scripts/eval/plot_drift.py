#!/usr/bin/env python3
"""Per-recording walking-speed drift at a single unpinned upgrade.

Reads drift-per-datapoint.csv (version x recording -> walking speed) and draws a diverging
lollipop: one row per recording, showing the percentage change in the walking-speed DMO when
the analysis library is upgraded from the pinned version to the next release, sorted by
magnitude and coloured by cohort. The dashed line at zero is what CARP-DSP guarantees by
pinning the environment; every recording off that line is a silent change an unpinned re-run
would inherit.

  python plot_drift.py drift-per-datapoint.csv fig-dependency-drift
"""
import sys
import csv
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

PREFERRED_BASE = "0.10.0"  # version the workflow was authored/pinned against, if present
COLOURS = {"HA": "#c0392b", "MS": "#2980b9"}
TOL = 1e-6  # a recording "changes" if its DMO moves by more than this (% scale)


def _vkey(v):
    return tuple(int(p) if p.isdigit() else 0 for p in v.split("."))


def load(path):
    ws = defaultdict(dict)
    cohort = {}
    with open(path) as f:
        for r in csv.DictReader(f):
            ws[r["datapoint"]][r["version"]] = float(r["walking_speed_mps"])
            cohort[r["datapoint"]] = r["cohort"]
    return ws, cohort


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "drift-per-datapoint.csv"
    out = sys.argv[2] if len(sys.argv) > 2 else "fig-dependency-drift"
    ws, cohort = load(src)
    order = sorted({v for d in ws.values() for v in d}, key=_vkey)
    pin = PREFERRED_BASE if PREFERRED_BASE in order else order[0]
    i = order.index(pin)
    compare = order[i + 1] if i + 1 < len(order) else order[i - 1]  # the "next release"

    pct = {d: (ws[d][compare] - ws[d][pin]) / ws[d][pin] * 100 for d in ws}
    recs = sorted(ws, key=lambda d: pct[d])
    changed = sum(1 for d in recs if abs(pct[d]) > TOL)

    fig, ax = plt.subplots(figsize=(6.6, 3.6))
    for y, d in enumerate(recs):
        c = COLOURS.get(cohort[d], "#555555")
        ax.plot([0, pct[d]], [y, y], "-", color=c, lw=1.6, zorder=2)
        ax.plot(pct[d], y, "o", color=c, ms=7, zorder=3)
    ax.axvline(0, ls="--", color="#2c3e50", lw=1.4, zorder=1)

    ax.set_yticks(range(len(recs)))
    ax.set_yticklabels([d.replace("-Trial", " T").replace("-Test", " Test") for d in recs], fontsize=7.5)
    ax.set_xlabel(f"Change in walking speed when upgrading from pinned v{pin} to v{compare} (\\%)"
                  .replace("\\%", "%"))
    ax.margins(y=0.04)
    ax.grid(axis="x", ls=":", alpha=0.5)
    for s in ("top", "right", "left"):
        ax.spines[s].set_visible(False)
    ax.tick_params(axis="y", length=0)

    handles = [Line2D([0], [0], color="#2c3e50", ls="--", lw=1.4, label=f"Pinned by CARP-DSP (v{pin})")]
    handles += [Line2D([0], [0], color=COLOURS[c], marker="o", lw=1.6, label=f"{c} cohort")
                for c in ("HA", "MS") if c in cohort.values()]
    ax.legend(handles=handles, loc="lower right", fontsize=8, frameon=False)

    lo, hi = min(pct.values()), max(pct.values())
    span = (hi - lo) or 1.0
    ax.set_xlim(min(lo, 0) - 0.08 * span - 0.3, hi + 0.10 * span + 0.3)
    ax.set_title(f"A single upgrade (v{pin}→v{compare}) silently shifts {changed} of {len(recs)} recordings",
                 fontsize=9, loc="left")

    fig.tight_layout()
    for ext in ("png", "pdf"):
        fig.savefig(f"{out}.{ext}", dpi=200, bbox_inches="tight")
    print(f"wrote {out}.png and {out}.pdf")


if __name__ == "__main__":
    main()
