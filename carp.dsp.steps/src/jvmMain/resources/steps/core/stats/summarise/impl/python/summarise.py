#!/usr/bin/env python3
"""core.stats.summarise - descriptive statistics for a tabular dataset.

Reports count, mean, median, standard deviation, minimum and maximum for every
numeric column, alongside the row count.

Inputs:
    --input     CSV table with a header row
Outputs:
    --output    JSON summary

Optional:
    --columns   comma-separated subset of columns to summarise (default: all
                numeric columns)
"""
from __future__ import annotations

import argparse
import json
from typing import Any

import pandas as pd

# Rounding keeps the output stable across platforms. Ten decimal places sits far
# below the 1e-6 equivalence tolerance while removing last-bit noise that would
# otherwise make the reference fixture platform-dependent.
_ROUND = 10


def summarise(frame: pd.DataFrame, columns: list[str] | None = None) -> dict[str, Any]:
    """Return descriptive statistics for the numeric columns of `frame`.

    Args:
        frame: the table to summarise.
        columns: optional subset of column names. Non-numeric columns are
            ignored even when named explicitly, so callers cannot ask for
            statistics that do not exist.

    Returns:
        A mapping with the row count and, per numeric column, its statistics.
        Columns are emitted in sorted order so the output is stable.
    """
    numeric = frame.select_dtypes(include="number")
    if columns is not None:
        numeric = numeric[[c for c in columns if c in numeric.columns]]

    stats: dict[str, Any] = {}
    for name in sorted(numeric.columns):
        series = numeric[name].dropna()
        stats[str(name)] = {
            "n": int(series.count()),
            "mean": _round(series.mean()),
            "median": _round(series.median()),
            "sd": _round(series.std(ddof=1)) if series.count() > 1 else None,
            "min": _round(series.min()),
            "max": _round(series.max()),
        }

    return {"n_rows": int(len(frame)), "columns": stats}


def _round(value: Any) -> float | None:
    """Round to a stable precision, mapping missing values to None."""
    if pd.isna(value):
        return None
    return round(float(value), _ROUND)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="CSV table to summarise")
    parser.add_argument("--output", required=True, help="JSON summary output path")
    parser.add_argument(
        "--columns",
        default=None,
        help="comma-separated subset of columns (default: all numeric columns)",
    )
    args = parser.parse_args()

    columns = [c.strip() for c in args.columns.split(",")] if args.columns else None
    summary = summarise(pd.read_csv(args.input), columns)

    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")


if __name__ == "__main__":
    main()
