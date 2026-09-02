#!/usr/bin/env python3
"""Per-recording walking-speed drift at a single unpinned upgrade.

Reads drift-per-datapoint.csv (version x recording -> walking speed) and draws a
horizontal bar chart: one bar per recording, showing the **absolute** change in
the walking-speed DMO when the analysis library is upgraded from the pinned
version to the next release, sorted largest first.

Two reference lines mark published thresholds for meaningful change in gait
speed (Perera et al. 2006: ~0.05 m/s small, ~0.10 m/s substantial). Those come
from supervised walking tests in older adults, not from this cohort, so they are
a scale for judging magnitude rather than a claim about these participants - the
caption says so too.

Design notes, since this replaces a diverging lollipop:

  * **Absolute values, not signed percentages.** The thresholds are absolute and
    in m/s, so plotting relative change invited a comparison in mismatched units.
    Direction is not the point; magnitude against a known scale is.
  * **Sorted by magnitude**, so the reader's eye lands on the largest deviation
    rather than on an arbitrary recording order.
  * **No cohort colours.** They implied the cohort split explained the drift. It
    does not - the split is visible in the labels for anyone who wants it, and
    colouring by it made a decorative variable look like a finding.

  python plot_drift.py drift-per-datapoint.csv fig-dependency-drift
"""
import sys
import csv
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

PREFERRED_BASE = "0.10.0"  # version the workflow was authored/pinned against, if present
TOL = 1e-6                 # a recording "changes" if its DMO moves by more than this (m/s)

# Perera et al. 2006, gait speed in older adults: small and substantial
# meaningful change. Different measurement context from this dataset; used as a
# reference scale only.
THRESHOLDS = [(0.05, "small"), (0.10, "substantial")]

BAR = "#4a6785"
RULE = "#2c3e50"


def _vkey(v):
    return tuple(int(p) if p.isdigit() else 0 for p in v.split("."))


def load(path):
    ws = defaultdict(dict)
    with open(path) as f:
        for r in csv.DictReader(f):
            if r.get("walking_speed_mps"):
                ws[r["datapoint"]][r["version"]] = float(r["walking_speed_mps"])
    return ws


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "drift-per-datapoint.csv"
    out = sys.argv[2] if len(sys.argv) > 2 else "fig-dependency-drift"
    ws = load(src)
    order = sorted({v for d in ws.values() for v in d}, key=_vkey)
    pin = PREFERRED_BASE if PREFERRED_BASE in order else order[0]
    i = order.index(pin)
    compare = order[i + 1] if i + 1 < len(order) else order[i - 1]  # the "next release"

    delta = {d: ws[d][compare] - ws[d][pin] for d in ws if pin in ws[d] and compare in ws[d]}
    # Largest at the top: barh draws upward, so sort ascending and let it invert.
    recs = sorted(delta, key=lambda d: abs(delta[d]))
    changed = sum(1 for d in recs if abs(delta[d]) > TOL)
    mean_abs = sum(abs(delta[d]) for d in recs) / len(recs)

    fig, ax = plt.subplots(figsize=(6.6, 3.4))
    ax.barh(range(len(recs)), [abs(delta[d]) for d in recs], color=BAR, height=0.62, zorder=3)

    # Labels go above the axes, not inside them: at 0.10 the line crosses the
    # longest bar, and an annotation there sits on top of the value it describes.
    for value, name in THRESHOLDS:
        ax.axvline(value, ls="--", color=RULE, lw=1.2, zorder=2)
        ax.annotate(name, xy=(value, 1.0), xycoords=("data", "axes fraction"),
                    xytext=(0, 4), textcoords="offset points",
                    fontsize=7.5, color=RULE, va="bottom", ha="center")

    ax.set_yticks(range(len(recs)))
    ax.set_yticklabels([d.replace("-Trial", " T").replace("-Test", " Test") for d in recs],
                       fontsize=7.5)
    ax.set_xlabel(f"Absolute change in walking speed, pinned v{pin} to v{compare} (m/s)")
    ax.margins(y=0.06)
    ax.grid(axis="x", ls=":", alpha=0.5, zorder=0)
    for s in ("top", "right", "left"):
        ax.spines[s].set_visible(False)
    ax.tick_params(axis="y", length=0)

    # No legend: with the lines labelled above the axes there is nothing left for
    # it to explain, and the caption carries the citation. A legend here sat on
    # the 0.10 line it was describing.
    ax.set_title(
        f"A single upgrade (v{pin}\u2192v{compare}) shifts {changed} of {len(recs)} recordings; "
        f"mean |\u0394| {mean_abs * 1000:.0f} mm/s",
        fontsize=9, loc="left", pad=18,
    )

    fig.tight_layout()
    for ext in ("png", "pdf"):
        fig.savefig(f"{out}.{ext}", dpi=200, bbox_inches="tight")
    print(f"wrote {out}.png and {out}.pdf  ({changed}/{len(recs)} changed, mean |d| {mean_abs:.4f} m/s)")


if __name__ == "__main__":
    main()
