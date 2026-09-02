#!/usr/bin/env python3
"""Library step: core.viz.visualise.

Plot a table as a small multi-panel figure. Every panel is named by an argument,
so the step holds no knowledge of what the columns measure.

Inputs:
    --input      CSV table with a header row
Outputs:
    --output     PNG figure

Panels, in the order they are drawn:
    --line       col,...      mean of the column per --time-column value
    --histogram  col,...      distribution of the column
    --scatter    x:y,...      one column against another
"""
from __future__ import annotations

import argparse
import math

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402 - must follow the backend selection
import pandas as pd  # noqa: E402

DEFAULT_TIME_COLUMN = "date"
HISTOGRAM_BINS = 20


def parse_list(spec: str) -> list[str]:
    return [part.strip() for part in spec.split(",") if part.strip()] if spec else []


def parse_pairs(spec: str) -> list[tuple[str, str]]:
    pairs = []
    for part in parse_list(spec):
        if ":" not in part:
            raise SystemExit(f"[visualise] bad --scatter entry '{part}', expected 'x:y'")
        x, y = part.split(":", 1)
        pairs.append((x.strip(), y.strip()))
    return pairs


def require(frame: pd.DataFrame, columns: list[str], option: str) -> None:
    """Stop on a column that is not there, rather than drawing a panel short.

    A missing panel in a figure is easy to overlook, and the figure still looks
    finished, so a typo would otherwise be reported by nothing at all.
    """
    missing = [c for c in columns if c not in frame.columns]
    if missing:
        raise SystemExit(
            f"[visualise] {option} refers to column(s) not in the table: "
            f"{', '.join(missing)}. Available: {', '.join(frame.columns)}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="CSV table with a header row")
    parser.add_argument("--output", required=True, help="PNG output path")
    parser.add_argument("--time-column", default=DEFAULT_TIME_COLUMN,
                        help="column the line panels are grouped by")
    parser.add_argument("--line", default="", help="col,... mean per --time-column value")
    parser.add_argument("--histogram", default="", help="col,... distribution")
    parser.add_argument("--scatter", default="", help="x:y,... one column against another")
    args = parser.parse_args()

    lines = parse_list(args.line)
    histograms = parse_list(args.histogram)
    scatters = parse_pairs(args.scatter)

    if not (lines or histograms or scatters):
        raise SystemExit(
            "[visualise] nothing to plot - pass at least one of --line, --histogram "
            "or --scatter"
        )

    frame = pd.read_csv(args.input)
    if lines:
        require(frame, [args.time_column], "--time-column")
        frame[args.time_column] = pd.to_datetime(frame[args.time_column])
    require(frame, lines, "--line")
    require(frame, histograms, "--histogram")
    require(frame, [c for pair in scatters for c in pair], "--scatter")

    panels = len(lines) + len(histograms) + len(scatters)
    columns = math.ceil(math.sqrt(panels))
    rows = math.ceil(panels / columns)
    figure, axes = plt.subplots(rows, columns, figsize=(4.5 * columns, 3 * rows), squeeze=False)
    flat = axes.flat

    grouped = frame.groupby(args.time_column) if lines else None
    for column in lines:
        axis = next(flat)
        axis.plot(grouped[column].mean())
        axis.set_title(f"Mean {column} per {args.time_column}")

    for column in histograms:
        axis = next(flat)
        axis.hist(frame[column].dropna(), bins=HISTOGRAM_BINS)
        axis.set_title(f"{column} distribution")

    for x, y in scatters:
        axis = next(flat)
        axis.scatter(frame[x], frame[y], s=8, alpha=0.5)
        axis.set_title(f"{x} vs {y}")
        axis.set_xlabel(x)
        axis.set_ylabel(y)

    for axis in flat:          # any cells the panel count did not fill
        axis.set_visible(False)
    for axis in axes.flat:
        axis.tick_params(labelsize=8)

    figure.tight_layout()
    figure.savefig(args.output, dpi=120)
    print(f"[visualise] {panels} panel(s) -> {args.output}")


if __name__ == "__main__":
    main()
